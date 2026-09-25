package io.github.youndie.kesh.server.net

import io.github.youndie.kesh.server.http.HttpResponse
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import platform.linux.EPOLLERR
import platform.linux.EPOLLHUP
import platform.linux.EPOLLIN
import platform.linux.EPOLLOUT
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.close
import platform.posix.errno
import platform.posix.read
import platform.posix.write
import kotlin.time.TimeSource

/**
 * One request on the HTTP port (endpoint-http), on the [EventLoop]: probes and metrics for the
 * cluster. Not a web server — `GET`, one request per connection, `Connection: close` — which is all a
 * kubelet and a Prometheus scrape send. A request whose head is not whole within [MAX_REQUEST] bytes
 * is dropped, and so is one older than [MAX_AGE_MS] ([expired], checked by the periodic work).
 */
@OptIn(ExperimentalForeignApi::class)
internal class HttpConnection(
    val fd: Int,
    private val loop: EventLoop,
    private val scope: CoroutineScope,
    private val route: suspend (method: String, path: String) -> HttpResponse,
    private val closed: (HttpConnection) -> Unit,
) : EventLoop.Handler {
    private val opened = TimeSource.Monotonic.markNow()
    private val head = ByteArray(MAX_REQUEST)
    private var filled = 0
    private var answering = false
    private var out = ByteArray(0)
    private var written = 0
    private var open = true

    val expired: Boolean get() = opened.elapsedNow().inWholeMilliseconds > MAX_AGE_MS

    override fun onEvent(events: UInt) {
        if (!open) return
        if (!answering && events and (EPOLLIN or EPOLLHUP or EPOLLERR) != 0u) readHead()
        if (open && answering && events and EPOLLOUT != 0u) flush()
    }

    fun close() {
        if (!open) return
        open = false
        loop.remove(fd)
        close(fd)
        closed(this)
    }

    private fun readHead() {
        while (filled < MAX_REQUEST) {
            val n = head.usePinned { read(fd, it.addressOf(filled), (MAX_REQUEST - filled).toULong()) }
            when {
                n > 0 -> {
                    filled += n.toInt()
                    val text = head.decodeToString(0, filled)
                    if ("\r\n\r\n" in text) {
                        respond(text)
                        return
                    }
                }

                n < 0 && errno == EAGAIN -> {
                    return
                }

                n < 0 && errno == EINTR -> {}

                else -> {
                    close()
                    return
                }
            }
        }
        close()
    }

    private fun respond(text: String) {
        answering = true
        val requestLine = text.substringBefore("\r\n").split(' ')
        scope.launch(loop) {
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
            out = header.encodeToByteArray() + body
            if (open) {
                loop.modify(fd, EPOLLOUT)
                flush()
            }
        }
    }

    private fun flush() {
        while (written < out.size) {
            val n = out.usePinned { write(fd, it.addressOf(written), (out.size - written).toULong()) }
            when {
                n > 0 -> {
                    written += n.toInt()
                }

                n < 0 && errno == EAGAIN -> {
                    return
                }

                n < 0 && errno == EINTR -> {}

                else -> {
                    break
                }
            }
        }
        close()
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
        const val MAX_AGE_MS = 5_000L
    }
}
