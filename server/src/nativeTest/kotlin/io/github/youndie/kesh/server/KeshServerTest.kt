package io.github.youndie.kesh.server

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.time.Duration.Companion.seconds

/** Through a real socket, the way `redis-cli` reaches the server. */
class KeshServerTest {
    private suspend fun ByteReadChannel.readExactly(count: Int): String {
        val out = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val n = readAvailable(out, filled, count - filled)
            check(n >= 0) { "closed after $filled of $count bytes: ${out.decodeToString(0, filled)}" }
            filled += n
        }
        return out.decodeToString()
    }

    private fun withServer(block: suspend (port: Int, selector: SelectorManager) -> Unit) =
        runBlocking {
            val server = KeshServer(ServerConfig(host = "127.0.0.1", port = 0))
            server.start()
            val selector = SelectorManager(Dispatchers.IO)
            try {
                withTimeout(10.seconds) { block(server.port, selector) }
            } finally {
                server.stop()
                server.close()
                selector.close()
            }
        }

    @Test
    fun `PING over TCP answers PONG`() =
        withServer { port, selector ->
            val socket = aSocket(selector).tcp().connect("127.0.0.1", port)
            val output = socket.openWriteChannel(autoFlush = true)
            val input = socket.openReadChannel()

            output.writeFully("*1\r\n$4\r\nPING\r\n".encodeToByteArray())

            assertEquals("+PONG\r\n", input.readExactly(7))
            socket.close()
        }

    @Test
    fun `pipelined commands are answered in the order they were sent`() =
        withServer { port, selector ->
            val socket = aSocket(selector).tcp().connect("127.0.0.1", port)
            val output = socket.openWriteChannel(autoFlush = true)
            val input = socket.openReadChannel()
            val expected = (1..200).joinToString("") { "$${"m$it".length}\r\nm$it\r\n" }

            output.writeFully(
                (1..200).joinToString("") { "*2\r\n$4\r\nPING\r\n$${"m$it".length}\r\nm$it\r\n" }.encodeToByteArray(),
            )

            assertEquals(expected, input.readExactly(expected.encodeToByteArray().size))
            socket.close()
        }

    @Test
    fun `a protocol error is answered and the connection closed`() =
        withServer { port, selector ->
            val socket = aSocket(selector).tcp().connect("127.0.0.1", port)
            val output = socket.openWriteChannel(autoFlush = true)
            val input = socket.openReadChannel()
            val error = "-ERR Protocol error: invalid bulk length\r\n"

            output.writeFully("*1\r\n\$x\r\n".encodeToByteArray())

            assertEquals(error, input.readExactly(error.length))
            assertEquals(-1, input.readAvailable(ByteArray(1), 0, 1))
            socket.close()
        }

    @Test
    fun `after the drain the listener accepts nothing`() =
        runBlocking {
            val server = KeshServer(ServerConfig(host = "127.0.0.1", port = 0))
            server.start()
            val port = server.port
            server.stop()
            server.close()

            val selector = SelectorManager(Dispatchers.IO)
            assertFails { aSocket(selector).tcp().connect("127.0.0.1", port) }
            selector.close()
        }
}
