package io.github.youndie.kesh.server

import io.github.youndie.kesh.server.command.CommandDispatcher
import io.github.youndie.kesh.server.connection.Connection
import io.github.youndie.kore.lifecycle.ShutdownParticipant
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
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

    // A failure inside one connection ends that connection and is reported; it must not reach the
    // runtime, which on Kotlin/Native terminates the process for an uncaught coroutine exception.
    private val failureReport =
        CoroutineExceptionHandler { _, failure -> println("kesh: connection failed: $failure") }
    private val connections = CoroutineScope(SupervisorJob() + Dispatchers.IO + failureReport)
    private var listener: ServerSocket? = null

    /** The bound port — the configured one, or the one the OS chose for port 0. */
    val port: Int
        get() = (checkNotNull(listener) { "not started" }.localAddress as InetSocketAddress).port

    suspend fun start() {
        check(listener == null) { "already started" }
        // SO_REUSEADDR, as Redis sets it (`redis/redis@7.2!/src/anet.c` — `anetSetReuseAddr`). A
        // connection the server closed leaves the server's port in TIME-WAIT, and without the flag a
        // restart inside that window dies at startup with EADDRINUSE. ktor's default is off.
        val socket = aSocket(selector).tcp().bind(config.host, config.port) { reuseAddress = true }
        listener = socket
        connections.launch {
            while (true) {
                val client = socket.accept()
                connections.launch { Connection(client, storeThread, commands).serve() }
            }
        }
    }

    /**
     * Cancel first, close second. Closed first, the listener completes asynchronously: a suspended
     * `accept` fails with `IOException: Accept failed` while the socket does not yet report itself
     * closed, and that exception escaping the loop killed the process in 4 of 8 test runs. Cancelled
     * first, `accept` ends with a cancellation, which is the loop's normal way out.
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
}
