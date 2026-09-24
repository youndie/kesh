package io.github.youndie.kesh.server

import io.github.youndie.kesh.resp.CommandReader
import io.github.youndie.kesh.server.client.Clients
import io.github.youndie.kesh.server.client.DescriptorCeiling
import io.github.youndie.kesh.server.command.CommandDispatcher
import io.github.youndie.kesh.server.connection.Connection
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

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

    /** The bound port — the configured one, or the one the OS chose for port 0. */
    val port: Int
        get() = (checkNotNull(listener) { "not started" }.localAddress as InetSocketAddress).port

    /** The `maxclients` in force: configured, or derived at startup. */
    val maxClients: Int get() = checkNotNull(registry) { "not started" }.maxClients

    suspend fun start() {
        check(listener == null) { "already started" }
        // SO_REUSEADDR, as Redis sets it (`redis/redis@7.2!/src/anet.c` — `anetSetReuseAddr`). A
        // connection the server closed leaves the server's port in TIME-WAIT, and without the flag a
        // restart inside that window dies at startup with EADDRINUSE. ktor's default is off.
        val socket = aSocket(selector).tcp().bind(config.host, config.port) { reuseAddress = true }
        listener = socket

        val ceiling = DescriptorCeiling.connectionCeiling()
        val maxClients = config.maxClients ?: ceiling
        require(maxClients <= ceiling) {
            "maxclients $maxClients is above what the transport can watch: $ceiling with the descriptors open " +
                "now (FD_SETSIZE ${DescriptorCeiling.FD_SETSIZE}, ${DescriptorCeiling.RESERVED} reserved)"
        }
        val clients = Clients(maxClients, passwordRequired = config.password != null)
        registry = clients
        val commands = CommandDispatcher(clients, config.password)

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
                connections.launch { serve(accepted, clients, commands) }
            }
        }
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
        connections.coroutineContext.job.cancelAndJoin()
        listener?.close()
    }

    /** Releases the threads. After [stop]; the process is about to end anyway. */
    fun close() {
        selector.close()
        storeThread.close()
    }

    private companion object {
        val MAX_CLIENTS_REACHED = "-ERR max number of clients reached\r\n".encodeToByteArray()
        val ACCEPT_RETRY = 100.milliseconds

        fun SocketAddress.text(): String =
            (this as? InetSocketAddress)?.let { "${it.hostname}:${it.port}" } ?: toString()
    }
}
