package io.github.youndie.kesh.server

import io.github.youndie.kesh.resp.RequestLimits
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** The scenarios of `feature-resp-connection`, through a real socket. */
class ConnectionScenariosTest {
    private class Client(
        val input: ByteReadChannel,
        val output: ByteWriteChannel,
    ) {
        suspend fun send(text: String) = output.writeFully(text.encodeToByteArray())

        suspend fun read(count: Int): String {
            val out = ByteArray(count)
            var filled = 0
            while (filled < count) {
                val n = input.readAvailable(out, filled, count - filled)
                check(n >= 0) { "closed after $filled of $count bytes: ${out.decodeToString(0, filled)}" }
                filled += n
            }
            return out.decodeToString()
        }

        suspend fun expect(text: String) = assertEquals(text, read(text.encodeToByteArray().size))

        /** The server closed the connection: end of stream, with nothing more to read. */
        suspend fun expectClosed() = assertEquals(-1, input.readAvailable(ByteArray(1), 0, 1))
    }

    private fun scenario(
        config: ServerConfig = ServerConfig(),
        block: suspend (connect: suspend () -> Client) -> Unit,
    ) = runBlocking {
        val server = KeshServer(config.copy(host = "127.0.0.1", port = 0))
        server.start()
        val selector = SelectorManager(Dispatchers.IO)
        try {
            withTimeout(20.seconds) {
                block {
                    val socket = aSocket(selector).tcp().connect("127.0.0.1", server.port)
                    Client(socket.openReadChannel(), socket.openWriteChannel(autoFlush = true))
                }
            }
        } finally {
            server.stop()
            server.close()
            selector.close()
        }
    }

    private fun command(vararg args: String) =
        "*${args.size}\r\n" + args.joinToString("") { "$${it.encodeToByteArray().size}\r\n$it\r\n" }

    @Test
    fun `a thousand pipelined INCR are answered 1 to 1000 in order`() =
        scenario { connect ->
            val c = connect()
            c.send(command("INCR", "k").repeat(1000))
            c.expect((1..1000).joinToString("") { ":$it\r\n" })
        }

    @Test
    fun `EXISTS typed into telnet answers 0 for a missing key`() =
        scenario { connect ->
            val c = connect()
            c.send("EXISTS somekey\r\n")
            c.expect(":0\r\n")
        }

    @Test
    fun `GET before AUTH is refused with NOAUTH`() =
        scenario(ServerConfig(password = "secret")) { connect ->
            val c = connect()
            c.send(command("GET", "k"))
            c.expect("-NOAUTH Authentication required.\r\n")
        }

    @Test
    fun `an inline command typed into telnet is answered`() =
        scenario { connect ->
            val c = connect()
            c.send("PING\r\nECHO \"two words\"\n")
            c.expect("+PONG\r\n$9\r\ntwo words\r\n")
        }

    @Test
    fun `with a password an unauthenticated command gets NOAUTH and AUTH opens the connection`() =
        scenario(ServerConfig(password = "secret")) { connect ->
            val c = connect()
            c.send(command("PING"))
            c.expect("-NOAUTH Authentication required.\r\n")
            c.send(command("AUTH", "secret") + command("PING"))
            c.expect("+OK\r\n+PONG\r\n")
        }

    @Test
    fun `AUTH lifts the unauthenticated limits for the very next command of the same pipeline`() =
        scenario(ServerConfig(password = "secret")) { connect ->
            val c = connect()
            val big = "x".repeat(20_000)
            c.send(command("AUTH", "secret") + command("ECHO", big))
            c.expect("+OK\r\n$20000\r\n$big\r\n")
        }

    @Test
    fun `an oversized request before AUTH is refused and the connection closed`() =
        scenario(ServerConfig(password = "secret")) { connect ->
            val c = connect()
            c.send("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$16385\r\n")
            c.expect("-ERR Protocol error: unauthenticated bulk length\r\n")
            c.expectClosed()
        }

    @Test
    fun `HELLO 3 is refused and the connection stays in RESP2`() =
        scenario { connect ->
            val c = connect()
            c.send(command("HELLO", "3") + command("PING"))
            c.expect("-NOPROTO unsupported protocol version\r\n+PONG\r\n")
        }

    @Test
    fun `the connection over maxclients is refused and the others keep being served`() =
        scenario(ServerConfig(maxClients = 3)) { connect ->
            val served = List(3) { connect() }
            served.forEach {
                it.send(command("PING"))
                it.expect("+PONG\r\n")
            }
            val extra = connect()
            extra.expect("-ERR max number of clients reached\r\n")
            extra.expectClosed()
            served.forEach {
                it.send(command("PING"))
                it.expect("+PONG\r\n")
            }
        }

    @Test
    fun `QUIT answers and closes and drops what was pipelined after it`() =
        scenario { connect ->
            val c = connect()
            c.send(command("QUIT") + command("PING"))
            c.expect("+OK\r\n")
            c.expectClosed()
        }

    @Test
    fun `an HTTP request aimed at the port is dropped without a reply`() =
        scenario { connect ->
            val c = connect()
            c.send("POST / HTTP/1.1\r\nHost: localhost\r\n\r\n")
            c.expectClosed()
        }

    @Test
    fun `CLIENT KILL from one connection closes another`() =
        scenario { connect ->
            val victim = connect()
            victim.send(command("CLIENT", "ID"))
            val id = victim.read(4).removePrefix(":").trim()
            val killer = connect()
            killer.send(command("CLIENT", "KILL", "ID", id))
            killer.expect(":1\r\n")
            victim.expectClosed()
        }

    @Test
    fun `a connection holding more than client-query-buffer-limit unparsed is closed`() =
        scenario(ServerConfig(queryBufferLimit = 1024)) { connect ->
            val c = connect()
            c.send("*1\r\n$4096\r\n" + "x".repeat(2048))
            c.expectClosed()
        }

    @Test
    fun `garbage on hundreds of connections leaves the server answering`() =
        scenario(ServerConfig(limits = RequestLimits(inlineMaxSize = 256))) { connect ->
            repeat(200) { case ->
                val random = Random(case)
                val c = connect()
                c.send(CharArray(random.nextInt(1, 300)) { random.nextInt(0, 128).toChar() }.concatToString() + "\r\n")
            }
            val c = connect()
            c.send(command("PING"))
            c.expect("+PONG\r\n")
        }
}
