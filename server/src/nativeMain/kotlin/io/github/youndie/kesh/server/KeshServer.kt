package io.github.youndie.kesh.server

import io.github.youndie.kesh.resp.CommandReader
import io.github.youndie.kesh.server.client.Clients
import io.github.youndie.kesh.server.command.CommandDispatcher
import io.github.youndie.kesh.server.command.epochMillis
import io.github.youndie.kesh.server.http.HttpResponse
import io.github.youndie.kesh.server.http.Metrics
import io.github.youndie.kesh.server.net.EventLoop
import io.github.youndie.kesh.server.net.HttpConnection
import io.github.youndie.kesh.server.net.RespConnection
import io.github.youndie.kesh.server.net.Sockets
import io.github.youndie.kesh.server.persistence.Persistence
import io.github.youndie.kesh.server.persistence.SnapshotFile
import io.github.youndie.kesh.server.persistence.SnapshotIOException
import io.github.youndie.kesh.snapshot.SnapshotException
import io.github.youndie.kesh.store.Db
import io.github.youndie.kesh.store.eviction.Eviction
import io.github.youndie.kesh.store.expiry.ActiveExpiry
import io.github.youndie.kore.health.LivenessGate
import io.github.youndie.kore.health.ReadinessGate
import io.github.youndie.kore.health.StartupGate
import io.github.youndie.kore.lifecycle.ShutdownParticipant
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import platform.linux.EPOLLIN
import platform.posix.close
import platform.posix.write
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The RESP listener and everything it owns: the event loop, the connections, the client registry,
 * the HTTP port.
 *
 * **One thread does everything a client can see** (research D-14, D-31): the [EventLoop] reads,
 * parses, executes and writes on its own thread, which is the store thread. Nothing else touches data
 * or the client registry, which is what makes each command atomic without a lock. What other code
 * needs of the store — the periodic work, `/metrics`, the save the drain ends with — runs on the same
 * loop as a coroutine.
 *
 * **The connection ceiling is Redis's rule** (D-31, amending D-13): `maxclients` is 10 000 by
 * default, lowered to what the descriptor limit (`RLIMIT_NOFILE`) allows less [RESERVED]; a configured
 * value above that is refused at startup. The connection over it is told
 * `-ERR max number of clients reached` and closed, as Redis does.
 *
 * As a [ShutdownParticipant] it is kore's drain stage: stop accepting, let every connection write
 * what it was told and close, then save if configured (B-16).
 */
@OptIn(ExperimentalForeignApi::class)
class KeshServer(
    private val config: ServerConfig,
) : ShutdownParticipant {
    override val name: String = "resp-listener"

    private val loop = EventLoop()

    // A failure inside one piece of periodic or HTTP work ends that piece and is reported; it must
    // not reach the runtime, which on Kotlin/Native terminates the process for an uncaught exception.
    private val failureReport =
        CoroutineExceptionHandler {
            _,
            failure,
            ->
            println("kesh: background work failed: $failure")
        }
    private val background = CoroutineScope(SupervisorJob() + loop + failureReport)
    private val periodic = ArrayList<Job>()

    private var listener = -1
    private var httpListener = -1
    private var boundPort = 0
    private var registry: Clients? = null

    // The loop's thread only.
    private val respConnections = HashSet<RespConnection>()
    private val httpConnections = HashSet<HttpConnection>()

    /** Traffic wanted? False until the start completes, and again from the announce stage on (kore). */
    val readiness = ReadinessGate()

    /** Wedged? Nothing in kesh declares itself so yet; the probe is there for when something can. */
    val liveness = LivenessGate()

    /** Started? The snapshot loaded and the RESP listener bound — a latch, as kore's startup probe is. */
    val startup = StartupGate(setOf(GATE_SNAPSHOT, GATE_LISTENER))

    /** The HTTP port once bound, the configured one or the one the OS chose for 0; `null` if disabled. */
    var httpPort: Int? = null
        private set

    /** The store's numbers for `/metrics`, copied on the store thread; set once the server has started. */
    private var storeMetrics: (suspend () -> Metrics.Store)? = null

    /**
     * What the drain ends with: kill a background save's child, then save if `KESH_SAVE_ON_SHUTDOWN` is
     * on; set once the server has started.
     */
    private var saveOnStop: (suspend () -> Unit)? = null

    /** The bound port — the configured one, or the one the OS chose for port 0. */
    val port: Int
        get() {
            check(boundPort != 0) { "not started" }
            return boundPort
        }

    /** The `maxclients` in force: configured, or derived at startup. */
    val maxClients: Int get() = checkNotNull(registry) { "not started" }.maxClients

    suspend fun start() {
        check(boundPort == 0) { "already started" }
        // The HTTP port first, so the probes answer — not ready — while the snapshot loads (B-15).
        config.httpPort?.let { wanted ->
            val fd =
                try {
                    Sockets.listen(config.host, wanted)
                } catch (e: Sockets.Refused) {
                    throw StartupFailure("could not listen on ${config.host}:$wanted for HTTP: ${e.message}")
                }
            httpListener = fd
            httpPort = Sockets.port(fd)
            withContext(loop) { loop.add(fd, EPOLLIN) { acceptHttp() } }
        }
        // The snapshot is loaded before the listener exists (B-14): until loading ends, a client is
        // refused at connect, as the brief says — not answered `-LOADING` as Redis answers it. A
        // snapshot that is not whole stops the start; nothing it held is served.
        val db = Db(seed = Random.nextInt()).apply { maxMemory = config.maxMemory }
        val file = SnapshotFile(config.dir, config.dbFilename)
        db.now = epochMillis()
        val loaded =
            try {
                file.load(db)
            } catch (e: SnapshotException) {
                throw StartupFailure("the snapshot ${file.path} cannot be loaded: ${e.message}")
            } catch (e: SnapshotIOException) {
                throw StartupFailure("the snapshot ${file.path} cannot be read: ${e.message}")
            }
        loaded?.let { println("kesh: loaded ${it.keys} keys, ${it.bytes} bytes, from ${file.path} in ${it.millis} ms") }
        startup.completed(GATE_SNAPSHOT)

        // A port another process listens on is a start that cannot go on, not a crash (B-24): Redis
        // logs "Could not create server TCP listening socket" and exits 1, and so does kesh.
        listener =
            try {
                Sockets.listen(config.host, config.port)
            } catch (e: Sockets.Refused) {
                throw StartupFailure("could not listen on ${config.host}:${config.port}: ${e.message}")
            }
        boundPort = Sockets.port(listener)

        val ceiling = minOf(REDIS_MAXCLIENTS.toLong(), Sockets.descriptorLimit() - RESERVED).toInt()
        val maxClients = config.maxClients ?: ceiling
        require(maxClients <= ceiling) {
            "maxclients $maxClients is above what the descriptor limit allows: $ceiling " +
                "(RLIMIT_NOFILE ${Sockets.descriptorLimit()}, $RESERVED reserved)"
        }
        val clients = Clients(maxClients, passwordRequired = config.password != null)
        registry = clients
        val started = TimeSource.Monotonic.markNow()
        val expiry = ActiveExpiry { started.elapsedNow().inWholeMicroseconds }
        val eviction =
            Eviction(clock = { started.elapsedNow().inWholeMicroseconds }).apply {
                policy = config.maxMemoryPolicy
                samples = config.maxMemorySamples
            }
        val persistence =
            Persistence(file, ::epochMillis, loadedKeys = loaded?.keys ?: 0) { listOf(listener, httpListener) }
        // A background save still running at the stop is killed first, as Redis's `prepareForShutdown`
        // kills it: it would race the stop's own save to the rename (B-25).
        saveOnStop = {
            withContext(loop) {
                persistence.killChild()
                if (config.saveOnShutdown) persistence.save(db)
            }
        }
        val commands =
            CommandDispatcher(
                clients,
                config.password,
                db,
                expiry = expiry,
                persistence = persistence,
                eviction = eviction,
                port = { port },
            )
        storeMetrics = {
            withContext(loop) {
                Metrics.Store(
                    usedMemory = db.usedMemory,
                    maxMemory = db.maxMemory,
                    keys = db.size.toLong(),
                    expires = db.expires.size.toLong(),
                    expiredKeys = db.expiredKeys,
                    evictedKeys = eviction.evictedKeys,
                    connectedClients = clients.size.toLong(),
                    connectionsReceived = clients.registered,
                    rejectedConnections = clients.rejected,
                    pubsubChannels = commands.pubsub.channelCount.toLong(),
                    pubsubPatterns = commands.pubsub.patternCount.toLong(),
                    commands = commands.stats.snapshot(),
                )
            }
        }

        // Redis's `serverCron` work for the data (B-13): the slow active expiry cycle and the tables'
        // resizing, HZ times a second, on the loop between commands — never inside one. And the HTTP
        // requests that never finished their head.
        periodic +=
            background.launch {
                while (true) {
                    delay(1_000L / ActiveExpiry.HZ)
                    db.now = epochMillis()
                    expiry.cycle(db)
                    db.resizeAndRehash()
                    persistence.reap()
                    httpConnections.filter { it.expired }.forEach { it.close() }
                }
            }

        // Redis's `evictionTimeProc` (B-12): an eviction that stopped at its time limit, or one that
        // `CONFIG SET maxmemory` asked for, goes on in rounds between commands until it is done.
        periodic +=
            background.launch {
                while (true) {
                    val more =
                        if (!eviction.running) {
                            false
                        } else {
                            db.now = epochMillis()
                            eviction.proceed(db)
                        }
                    if (more) yield() else delay(1_000L / ActiveExpiry.HZ)
                }
            }

        withContext(loop) { loop.add(listener, EPOLLIN) { acceptResp(clients, commands) } }
        startup.completed(GATE_LISTENER)
    }

    /** Every connection waiting on the listener, registered or told the ceiling is reached. */
    private fun acceptResp(
        clients: Clients,
        commands: CommandDispatcher,
    ) {
        repeat(ACCEPTS_PER_TURN) {
            val fd = Sockets.accept(listener) ?: return
            val connection =
                RespConnection(fd, loop, CommandReader(config.limits), config.queryBufferLimit, commands) { closed ->
                    respConnections.remove(closed)
                    commands.pubsub.remove(closed.client)
                    clients.unregister(closed.client)
                }
            val client =
                clients.register(Sockets.address(fd, local = false), Sockets.address(fd, local = true)) {
                    connection.close()
                }
            if (client == null) {
                MAX_CLIENTS_REACHED.usePinned { write(fd, it.addressOf(0), MAX_CLIENTS_REACHED.size.toULong()) }
                close(fd)
                return@repeat
            }
            connection.client = client
            client.deliver = connection::deliver
            respConnections += connection
            loop.add(fd, EPOLLIN, connection)
        }
    }

    private fun acceptHttp() {
        repeat(ACCEPTS_PER_TURN) {
            val fd = Sockets.accept(httpListener) ?: return
            val connection = HttpConnection(fd, loop, background, ::route) { httpConnections.remove(it) }
            httpConnections += connection
            loop.add(fd, EPOLLIN, connection)
        }
    }

    /**
     * Stop accepting; let every connection write what it was told and close — a connection waiting
     * for its next command closes at once, because its commands run where they are read and none is
     * half done (B-16, D-31). One that does not read its replies is closed after [CONNECTION_DRAIN].
     * Then the snapshot, if configured, when no command can change the data any more.
     */
    override suspend fun stop() {
        withContext(loop) {
            if (listener >= 0) {
                loop.remove(listener)
                close(listener)
                listener = -1
            }
            respConnections.toList().forEach { it.closeAfterWriting() }
        }
        val drained =
            withTimeoutOrNull(CONNECTION_DRAIN) {
                while (withContext(loop) { respConnections.isNotEmpty() }) delay(10)
            }
        if (drained == null) {
            val stuck =
                withContext(loop) {
                    respConnections.toList().onEach { it.client.kill() }.size
                }
            println("kesh: drain: $stuck connections still writing after $CONNECTION_DRAIN; closed them")
        }
        saveOnStop?.invoke()
        periodic.forEach { it.cancelAndJoin() }
    }

    /** `GET` on the HTTP port: kore's three probes and the metrics. */
    private suspend fun route(
        method: String,
        path: String,
    ): HttpResponse =
        when (path) {
            "/health/live" -> {
                liveness.wedgedReason?.let { HttpResponse(503, "wedged: $it\n") } ?: HttpResponse(200, "alive\n")
            }

            "/health/started" -> {
                if (startup.hasStarted) {
                    HttpResponse(200, "started\n")
                } else {
                    HttpResponse(503, "starting: ${startup.pending.sorted().joinToString(", ")}\n")
                }
            }

            "/health/ready" -> {
                if (!startup.hasStarted) {
                    HttpResponse(503, "not ready: starting: ${startup.pending.sorted().joinToString(", ")}\n")
                } else {
                    val verdict = readiness.verdict()
                    HttpResponse(if (verdict.ready) 200 else 503, "$verdict\n")
                }
            }

            "/metrics" -> {
                HttpResponse(200, Metrics.render(storeMetrics?.invoke()), Metrics.CONTENT_TYPE)
            }

            else -> {
                HttpResponse(404, "no such path: $method $path\n")
            }
        }

    /** Releases the loop, its thread and the HTTP port. After [stop]; the process is about to end anyway. */
    fun close() {
        background.cancel()
        runBlocking {
            withContext(loop) {
                httpConnections.toList().forEach { it.close() }
                respConnections.toList().forEach { it.close() }
                for (fd in listOf(httpListener, listener)) {
                    if (fd >= 0) {
                        loop.remove(fd)
                        close(fd)
                    }
                }
                httpListener = -1
                listener = -1
            }
        }
        loop.close()
    }

    internal companion object {
        val MAX_CLIENTS_REACHED = "-ERR max number of clients reached\r\n".encodeToByteArray()
        const val GATE_SNAPSHOT = "snapshot"
        const val GATE_LISTENER = "listener"

        /** What the drain gives connections to write what they were told; the save gets the rest. */
        val CONNECTION_DRAIN = 5.seconds

        /** Redis's default `maxclients`. */
        const val REDIS_MAXCLIENTS = 10_000

        /** Descriptors kept for what is not a RESP connection, as Redis's `CONFIG_MIN_RESERVED_FDS`. */
        const val RESERVED = 32

        /** Connections taken from the listener per turn of the loop. */
        const val ACCEPTS_PER_TURN = 64
    }
}

/** A start that cannot go on — a taken port, a damaged snapshot: the server says why and exits 1. */
class StartupFailure(
    message: String,
) : Exception(message)
