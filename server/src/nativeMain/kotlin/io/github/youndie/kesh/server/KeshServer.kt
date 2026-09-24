package io.github.youndie.kesh.server

import io.github.youndie.kesh.server.command.CommandDispatcher
import io.github.youndie.kesh.server.connection.Connection
import io.github.youndie.kore.lifecycle.ShutdownParticipant
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.isClosed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext

/**
 * The RESP listener and everything it owns: the selector, the connections, and the store thread.
 *
 * **One thread executes every command** (research D-14). Connections read, parse and write on the
 * I/O side; each batch of parsed commands is handed to [storeThread] and its replies come back in
 * order. Nothing but that thread ever touches data, which is what makes each command atomic without a
 * lock. Even `PING` goes through the hand-off, so the guarantee is structural from the first command.
 *
 * As a [ShutdownParticipant] it is kore's drain stage: stop accepting, then end the connections.
 * Letting in-flight commands finish and their replies flush before that is B-16's graceful stop.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class KeshServer(
    private val config: ServerConfig,
    private val commands: CommandDispatcher = CommandDispatcher(),
) : ShutdownParticipant {
    override val name: String = "resp-listener"

    private val selector = SelectorManager(Dispatchers.IO)
    private val storeThread: CloseableCoroutineDispatcher = newSingleThreadContext("kesh-store")
    private val connections = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listener: ServerSocket? = null

    /** The bound port — the configured one, or the one the OS chose for port 0. */
    val port: Int
        get() = (checkNotNull(listener) { "not started" }.localAddress as InetSocketAddress).port

    suspend fun start() {
        check(listener == null) { "already started" }
        val socket = aSocket(selector).tcp().bind(config.host, config.port)
        listener = socket
        connections.launch {
            while (true) {
                val client =
                    try {
                        socket.accept()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The drain closed the listener: the accept loop ends, not the process.
                        if (socket.isClosed) break else throw e
                    }
                connections.launch { Connection(client, storeThread, commands).serve() }
            }
        }
    }

    override suspend fun stop() {
        listener?.close()
        connections.coroutineContext.job.cancelAndJoin()
    }

    /** Releases the threads. After [stop]; the process is about to end anyway. */
    fun close() {
        selector.close()
        storeThread.close()
    }
}
