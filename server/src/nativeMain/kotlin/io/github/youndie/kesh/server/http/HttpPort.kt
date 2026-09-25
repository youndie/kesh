package io.github.youndie.kesh.server.http

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** One answer of the HTTP port. */
class HttpResponse(
    val status: Int,
    val body: String,
    val contentType: String = "text/plain; charset=utf-8",
)

/**
 * The HTTP port (endpoint-http): probes and metrics for the cluster, on the RESP listener's selector.
 *
 * **Not a web server, and deliberately not Ktor's.** It answers `GET` for a handful of paths with
 * `Connection: close` — one request, one response, one socket — which is all a kubelet and a
 * Prometheus scrape send. A Ktor engine would bring its own selector and threads into a process whose
 * descriptor budget and signal handling are already tight (research D-13, R-3), for three routes.
 * A request that is not whole within [REQUEST_TIMEOUT] or [MAX_REQUEST] bytes is dropped.
 */
class HttpPort(
    private val selector: SelectorManager,
    private val scope: CoroutineScope,
    private val host: String,
    private val requestedPort: Int,
    private val route: suspend (method: String, path: String) -> HttpResponse,
) {
    private var listener: ServerSocket? = null

    /** The bound port — the configured one, or the one the OS chose for 0. */
    val port: Int
        get() = (checkNotNull(listener) { "not started" }.localAddress as InetSocketAddress).port

    /** Binds and starts answering; throws if the port cannot be bound. */
    suspend fun start() {
        val socket = aSocket(selector).tcp().bind(host, requestedPort) { reuseAddress = true }
        listener = socket
        scope.launch {
            while (true) {
                val accepted =
                    try {
                        socket.accept()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        println("kesh: http accept failed: $e")
                        delay(100.milliseconds)
                        continue
                    }
                scope.launch { answer(accepted) }
            }
        }
    }

    fun close() {
        listener?.close()
    }

    private suspend fun answer(socket: Socket) {
        try {
            val head = withTimeoutOrNull(REQUEST_TIMEOUT) { readHead(socket) } ?: return
            val requestLine = head.substringBefore("\r\n").split(' ')
            val response =
                if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/1.")) {
                    HttpResponse(400, "bad request\n")
                } else if (requestLine[0] != "GET") {
                    HttpResponse(405, "only GET\n")
                } else {
                    route(requestLine[0], requestLine[1].substringBefore('?'))
                }
            val body = response.body.encodeToByteArray()
            val header =
                "HTTP/1.1 ${response.status} ${reason(response.status)}\r\n" +
                    "Content-Type: ${response.contentType}\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n"
            val output = socket.openWriteChannel(autoFlush = false)
            output.writeFully(header.encodeToByteArray())
            output.writeFully(body)
            output.flush()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("kesh: http request failed: $e")
        } finally {
            socket.close()
        }
    }

    /** The request line and headers, up to the blank line; `null` if the peer closed or sent too much. */
    private suspend fun readHead(socket: Socket): String? {
        val input = socket.openReadChannel()
        val buffer = ByteArray(MAX_REQUEST)
        var filled = 0
        while (filled < MAX_REQUEST) {
            val n = input.readAvailable(buffer, filled, MAX_REQUEST - filled)
            if (n < 0) return null
            filled += n
            val text = buffer.decodeToString(0, filled)
            if ("\r\n\r\n" in text) return text
        }
        return null
    }

    private fun reason(status: Int): String =
        when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            503 -> "Service Unavailable"
            else -> "Unknown"
        }

    private companion object {
        const val MAX_REQUEST = 8 * 1024
        val REQUEST_TIMEOUT = 5.seconds
    }
}
