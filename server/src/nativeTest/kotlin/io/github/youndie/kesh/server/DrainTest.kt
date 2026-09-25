package io.github.youndie.kesh.server

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import platform.posix.mkdtemp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The drain stage (B-16): stop accepting, finish what was read, write every reply whole, then save. */
class DrainTest {
    private fun command(vararg args: String) =
        ("*${args.size}\r\n" + args.joinToString("") { "$${it.length}\r\n$it\r\n" }).encodeToByteArray()

    /**
     * What one client saw: how many `GET` replies arrived whole, and how many bytes were left over
     * after the last whole one — a truncated reply, if not 0.
     */
    private class Seen(
        val whole: Int,
        val leftover: Int,
    )

    @Test
    fun `under load every reply written before the drain ends is whole`() =
        runBlocking {
            val valueSize = 256 * 1024
            val server = KeshServer(ServerConfig(host = "127.0.0.1", port = 0))
            server.start()
            val selector = SelectorManager(Dispatchers.IO)
            val setup = aSocket(selector).tcp().connect("127.0.0.1", server.port)
            setup.openWriteChannel(autoFlush = true).writeFully(command("SET", "big", "x".repeat(valueSize)))
            check(setup.openReadChannel().readAvailable(ByteArray(16)) > 0)
            setup.close()

            val clients =
                (1..50).map {
                    async(Dispatchers.IO) {
                        val socket = aSocket(selector).tcp().connect("127.0.0.1", server.port)
                        val output = socket.openWriteChannel(autoFlush = true)
                        val input = socket.openReadChannel()
                        // A pipeline of four GETs, answered before the next four are sent — as a client with
                        // a bounded pipeline (redis-benchmark -P) talks — until the server closes the
                        // connection. Every reply is the same GET, so whole replies and a torn one are a
                        // matter of counting bytes: the total over one reply's size, and the remainder.
                        val header = "$$valueSize\r\n"
                        val frame = header.length + valueSize + 2
                        val four = command("GET", "big").let { one -> ByteArray(4 * one.size) { one[it % one.size] } }
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        var first = ""
                        serving@ while (true) {
                            try {
                                output.writeFully(four)
                            } catch (_: IOException) {
                                break // the server closed the connection
                            }
                            // A slow reader: the server's write of a megabyte of replies waits on the socket
                            // most of the time, which is where a drain that cut a write would cut it.
                            delay(20)
                            var owed = 4L * frame
                            while (owed > 0) {
                                val n =
                                    try {
                                        input.readAvailable(buffer, 0, minOf(buffer.size.toLong(), owed).toInt())
                                    } catch (_: IOException) {
                                        -1 // a reset is the end of what this client will receive
                                    }
                                if (n < 0) break@serving
                                if (first.length <
                                    header.length
                                ) {
                                    first += buffer.decodeToString(0, minOf(n, header.length))
                                }
                                total += n
                                owed -= n
                            }
                        }
                        socket.close()
                        check(total == 0L || first.startsWith(header)) { "not a GET reply: $first" }
                        Seen((total / frame).toInt(), (total % frame).toInt())
                    }
                }
            delay(500)
            withTimeout(30.seconds) { server.stop() }
            val seen = withTimeout(30.seconds) { clients.awaitAll() }
            server.close()
            selector.close()
            println("whole replies per client: ${seen.map { it.whole }}")
            assertTrue(seen.count { it.whole > 0 } >= 40, "most clients were served before the drain")
            assertEquals(List(50) { 0 }, seen.map { it.leftover }, "bytes of a truncated reply, per client")
        }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun `every command the drain lets run is answered - the saved counter equals the replies received`() =
        runBlocking {
            val dir = memScoped { mkdtemp("/tmp/kesh-ledger-XXXXXX".cstr.ptr)!!.toKString() }
            val config = ServerConfig(host = "127.0.0.1", port = 0, dir = dir, saveOnShutdown = true)
            val server = KeshServer(config)
            server.start()
            val selector = SelectorManager(Dispatchers.IO)
            val incr = command("INCR", "c")
            val four = ByteArray(4 * incr.size) { incr[it % incr.size] }
            val clients =
                (1..50).map {
                    async(Dispatchers.IO) {
                        val socket = aSocket(selector).tcp().connect("127.0.0.1", server.port)
                        val output = socket.openWriteChannel(autoFlush = true)
                        val input = socket.openReadChannel()
                        val buffer = ByteArray(4096)
                        var replies = 0
                        serving@ while (true) {
                            try {
                                output.writeFully(four)
                            } catch (_: IOException) {
                                break // the server closed the connection
                            }
                            var owed = 4
                            while (owed > 0) {
                                val n =
                                    try {
                                        input.readAvailable(buffer)
                                    } catch (_: IOException) {
                                        -1 // a reset is the end of what this client will receive
                                    }
                                if (n < 0) break@serving
                                // Integer replies, `:<n>\r\n`: one per line end.
                                val lines = (0 until n).count { buffer[it] == '\n'.code.toByte() }
                                replies += lines
                                owed -= lines
                            }
                        }
                        socket.close()
                        replies
                    }
                }
            delay(500)
            withTimeout(30.seconds) { server.stop() }
            val received = withTimeout(30.seconds) { clients.awaitAll() }.sum()
            server.close()

            val after = KeshServer(config.copy(saveOnShutdown = false))
            after.start()
            val socket = aSocket(selector).tcp().connect("127.0.0.1", after.port)
            socket.openWriteChannel(autoFlush = true).writeFully(command("GET", "c"))
            val reply = ByteArray(64)
            val n = socket.openReadChannel().readAvailable(reply)
            val counted = reply.decodeToString(0, n).lines()[1].toLong()
            socket.close()
            after.stop()
            after.close()
            selector.close()
            assertTrue(received > 1_000, "the clients were busy: $received replies")
            assertEquals(counted, received.toLong(), "INCRs executed against replies the clients received")
        }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun `the drain ends with a save when KESH_SAVE_ON_SHUTDOWN is on`() =
        runBlocking {
            val dir = memScoped { mkdtemp("/tmp/kesh-drain-XXXXXX".cstr.ptr)!!.toKString() }
            val config = ServerConfig(host = "127.0.0.1", port = 0, dir = dir, saveOnShutdown = true)
            val first = KeshServer(config)
            first.start()
            val selector = SelectorManager(Dispatchers.IO)
            val socket = aSocket(selector).tcp().connect("127.0.0.1", first.port)
            socket.openWriteChannel(autoFlush = true).writeFully(command("SET", "kept", "yes"))
            check(socket.openReadChannel().readAvailable(ByteArray(16)) > 0)
            socket.close()
            first.stop()
            first.close()

            val second = KeshServer(config.copy(saveOnShutdown = false))
            second.start()
            val again = aSocket(selector).tcp().connect("127.0.0.1", second.port)
            again.openWriteChannel(autoFlush = true).writeFully(command("GET", "kept"))
            val reply = ByteArray(16)
            val n = again.openReadChannel().readAvailable(reply)
            assertEquals("$3\r\nyes\r\n", reply.decodeToString(0, n))
            again.close()
            second.stop()
            second.close()
            selector.close()
        }
}
