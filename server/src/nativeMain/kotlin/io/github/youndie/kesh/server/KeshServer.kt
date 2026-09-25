package io.github.youndie.kesh.server

import io.github.youndie.kesh.resp.CommandReader
import io.github.youndie.kesh.server.client.Clients
import io.github.youndie.kesh.server.client.DescriptorCeiling
import io.github.youndie.kesh.server.command.CommandDispatcher
import io.github.youndie.kesh.server.command.epochMillis
import io.github.youndie.kesh.server.connection.Connection
import io.github.youndie.kesh.server.http.HttpPort
import io.github.youndie.kesh.server.http.HttpResponse
import io.github.youndie.kesh.server.http.Metrics
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
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.SocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The RESP listener and everything it owns: the selector, the connections, the client registry, and
 * the store thread.
 *
 * **One thread executes every command** (research D-14). Connections read, parse and write on the
 * I/O side; each batch of parsed commands is handed to [storeThread] and its replies come back in
 * order. Nothing but that thread touches data or the client registry, which is what makes each
 * command atomic without a lock.
 *
 * **The connection ceiling is kesh's, not the selector's** (research D-13). `maxclients` defaults to
 * what keeps every descriptor under `FD_SETSIZE` ([DescriptorCeiling]), and a configured value above
 * that is refused at startup rather than discovered as an exception inside the selector. The
 * connection over the ceiling is told `-ERR max number of clients reached` and closed, as Redis does.
 *
 * As a [ShutdownParticipant] it is kore's drain stage: stop accepting, then end the connections.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class KeshServer(
    private val config: ServerConfig,
) : ShutdownParticipant {
    override val name: String = "resp-listener"

    private val selector = SelectorManager(Dispatchers.IO)
    private val storeThread: CloseableCoroutineDispatcher = newSingleThreadContext("kesh-store")

    // A failure inside one connection ends that connection and is reported; it must not reach the
    // runtime, which on Kotlin/Native terminates the process for an uncaught coroutine exception.
    private val failureReport =
        CoroutineExceptionHandler { _, failure -> println("kesh: connection failed: $failure") }
    private val connections = CoroutineScope(SupervisorJob() + Dispatchers.IO + failureReport)
    private var listener: ServerSocket? = null
    private var registry: Clients? = null

    // The probes answer from before the snapshot load to after the drain, so they live in a scope of
    // their own that [stop] leaves alone (B-15).
    private val probes = CoroutineScope(SupervisorJob() + Dispatchers.IO + failureReport)
    private var http: HttpPort? = null

    /** Traffic wanted? False until the start completes, and again from the announce stage on (kore). */
    val readiness = ReadinessGate()

    /** Wedged? Nothing in kesh declares itself so yet; the probe is there for when something can. */
    val liveness = LivenessGate()

    /** Started? The snapshot loaded and the RESP listener bound — a latch, as kore's startup probe is. */
    val startup = StartupGate(setOf(GATE_SNAPSHOT, GATE_LISTENER))

    /** The HTTP port once bound, the configured one or the one the OS chose for 0; `null` if disabled. */
    val httpPort: Int? get() = http?.port

    /** The store's numbers for `/metrics`, copied on the store thread; set once the server has started. */
    private var storeMetrics: (suspend () -> Metrics.Store)? = null

    // The RESP connections, apart from the rest of the connection scope, so the drain can wait for
    // them alone (B-16).
    private val serving = SupervisorJob(connections.coroutineContext.job)
    private val clientScope = CoroutineScope(connections.coroutineContext + serving)
    private var accepting: Job? = null

    /** The save the drain ends with when `KESH_SAVE_ON_SHUTDOWN` is on; set once the server has started. */
    private var saveOnStop: (suspend () -> Unit)? = null

    /** The bound port — the configured one, or the one the OS chose for port 0. */
    val port: Int
        get() = (checkNotNull(listener) { "not started" }.localAddress as InetSocketAddress).port

    /** The `maxclients` in force: configured, or derived at startup. */
    val maxClients: Int get() = checkNotNull(registry) { "not started" }.maxClients

    suspend fun start() {
        check(listener == null) { "already started" }
        // The HTTP port first, so the probes answer — not ready — while the snapshot loads (B-15).
        config.httpPort?.let { httpPort ->
            val port = HttpPort(selector, probes, config.host, httpPort, ::route)
            try {
                port.start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw StartupFailure("could not listen on ${config.host}:$httpPort for HTTP: ${e.message}")
            }
            http = port
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

        // SO_REUSEADDR, as Redis sets it (`redis/redis@7.2!/src/anet.c` — `anetSetReuseAddr`). A
        // connection the server closed leaves the server's port in TIME-WAIT, and without the flag a
        // restart inside that window dies at startup with EADDRINUSE. ktor's default is off.
        // A port another process listens on is a start that cannot go on, not a crash (B-24): Redis
        // logs "Could not create server TCP listening socket" and exits 1, and so does kesh.
        val socket =
            try {
                aSocket(selector).tcp().bind(config.host, config.port) { reuseAddress = true }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw StartupFailure("could not listen on ${config.host}:${config.port}: ${e.message}")
            }
        listener = socket

        val ceiling = DescriptorCeiling.connectionCeiling()
        val maxClients = config.maxClients ?: ceiling
        require(maxClients <= ceiling) {
            "maxclients $maxClients is above what the transport can watch: $ceiling with the descriptors open " +
                "now (FD_SETSIZE ${DescriptorCeiling.FD_SETSIZE}, ${DescriptorCeiling.RESERVED} reserved)"
        }
        val clients = Clients(maxClients, passwordRequired = config.password != null)
        registry = clients
        // One keyspace, seeded per process so that keys chosen from outside cannot be made to collide.
        val started = TimeSource.Monotonic.markNow()
        val expiry = ActiveExpiry { started.elapsedNow().inWholeMicroseconds }
        val eviction =
            Eviction(clock = { started.elapsedNow().inWholeMicroseconds }).apply {
                policy = config.maxMemoryPolicy
                samples = config.maxMemorySamples
            }
        val persistence = Persistence(file, ::epochMillis, loadedKeys = loaded?.keys ?: 0)
        if (config.saveOnShutdown) saveOnStop = { withContext(storeThread) { persistence.save(db) } }
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
            withContext(storeThread) {
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
                    commands = commands.stats.snapshot(),
                )
            }
        }

        // Redis's `serverCron` work for the data (B-13): the slow active expiry cycle and the tables'
        // resizing, HZ times a second, on the store thread between commands — never inside one.
        connections.launch {
            while (true) {
                delay(1_000L / ActiveExpiry.HZ)
                withContext(storeThread) {
                    db.now = epochMillis()
                    expiry.cycle(db)
                    db.resizeAndRehash()
                }
            }
        }

        // Redis's `evictionTimeProc` (B-12): an eviction that stopped at its time limit, or one that
        // `CONFIG SET maxmemory` asked for, goes on in rounds between commands until it is done. Redis
        // arms a timer for it; kesh looks at the flag at the periodic work's pace, and every command
        // evicts for itself meanwhile.
        connections.launch {
            while (true) {
                val more =
                    withContext(storeThread) {
                        if (!eviction.running) return@withContext false
                        db.now = epochMillis()
                        eviction.proceed(db)
                    }
                if (more) yield() else delay(1_000L / ActiveExpiry.HZ)
            }
        }

        accepting =
            connections.launch {
                while (true) {
                    val accepted =
                        try {
                            socket.accept()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Out of descriptors (EMFILE) or a connection reset before it was accepted:
                            // Redis logs it and keeps listening, and so does kesh. The pause keeps a
                            // persistent condition from spinning.
                            println("kesh: accept failed: $e")
                            delay(ACCEPT_RETRY)
                            continue
                        }
                    clientScope.launch { serve(accepted, clients, commands) }
                }
            }
        startup.completed(GATE_LISTENER)
    }

    private suspend fun serve(
        socket: Socket,
        clients: Clients,
        commands: CommandDispatcher,
    ) {
        val input = socket.openReadChannel()
        val output = socket.openWriteChannel(autoFlush = false)
        val client =
            withContext(storeThread) {
                clients.register(socket.remoteAddress.text(), socket.localAddress.text(), socket::close)
            }
        try {
            if (client == null) {
                output.writeFully(MAX_CLIENTS_REACHED)
                output.flush()
                return
            }
            try {
                Connection(
                    input,
                    output,
                    client,
                    CommandReader(config.limits),
                    config.queryBufferLimit,
                    storeThread,
                    commands,
                ).serve()
            } finally {
                withContext(NonCancellable + storeThread) { clients.unregister(client) }
            }
        } finally {
            socket.close()
        }
    }

    /**
     * Cancel first, close second. Closed first, the listener completes asynchronously: a suspended
     * `accept` fails with `IOException: Accept failed` while the socket does not yet report itself
     * closed, and that exception escaping the loop killed the process in 4 of 8 test runs (B-01).
     */
    override suspend fun stop() {
        // Stop accepting first: nothing new arrives while the rest winds down.
        accepting?.cancelAndJoin()
        listener?.close()
        // Then the connections: a connection waiting for its next command is interrupted; one with a
        // batch read is not — `Connection` executes it and writes every reply whole, which is what
        // "no client receives a truncated reply" means (research R-3, B-16). A client that stops reading
        // its replies would hold that write for ever, so after [CONNECTION_DRAIN] its socket is closed.
        serving.children.forEach { it.cancel() }
        if (withTimeoutOrNull(CONNECTION_DRAIN) { serving.children.toList().joinAll() } == null) {
            val stuck =
                withContext(storeThread) {
                    registry
                        ?.all()
                        ?.toList()
                        .orEmpty()
                        .onEach { it.kill() }
                        .size
                }
            println("kesh: drain: $stuck connections still writing after $CONNECTION_DRAIN; closed them")
            serving.children.toList().joinAll()
        }
        // The snapshot last, when no command can change the data any more (KESH_SAVE_ON_SHUTDOWN).
        saveOnStop?.invoke()
        connections.coroutineContext.job.cancelAndJoin()
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

    /** Releases the threads and the HTTP port. After [stop]; the process is about to end anyway. */
    fun close() {
        probes.cancel()
        http?.close()
        selector.close()
        storeThread.close()
    }

    private companion object {
        val MAX_CLIENTS_REACHED = "-ERR max number of clients reached\r\n".encodeToByteArray()
        const val GATE_SNAPSHOT = "snapshot"

        /** What the drain gives connections to finish the batches they have read; the save gets the rest. */
        val CONNECTION_DRAIN = 5.seconds
        const val GATE_LISTENER = "listener"
        val ACCEPT_RETRY = 100.milliseconds

        fun SocketAddress.text(): String =
            (this as? InetSocketAddress)?.let { "${it.hostname}:${it.port}" } ?: toString()
    }
}

/** A start that cannot go on — a taken port, a damaged snapshot: the server says why and exits 1. */
class StartupFailure(
    message: String,
) : Exception(message)
