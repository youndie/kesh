package io.github.youndie.kesh.server

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** What the oracle cannot show: a subscriber that stops reading (B-27, Redis's pub/sub output limit). */
class PubSubTest {
    private fun command(vararg args: String) =
        ("*${args.size}\r\n" + args.joinToString("") { "$${it.length}\r\n$it\r\n" }).encodeToByteArray()

    @Test
    fun `a subscriber that stops reading is dropped past the output limit and leaves the registry`() =
        runBlocking {
            val server = KeshServer(ServerConfig(host = "127.0.0.1", port = 0))
            server.start()
            val selector = SelectorManager(Dispatchers.IO)
            try {
                withTimeout(60.seconds) {
                    val subscriber = aSocket(selector).tcp().connect("127.0.0.1", server.port)
                    val subIn = subscriber.openReadChannel()
                    subscriber.openWriteChannel(autoFlush = true).writeFully(command("SUBSCRIBE", "news"))
                    check(subIn.readAvailable(ByteArray(64)) > 0)
                    // From here the subscriber reads nothing.

                    val publisher = aSocket(selector).tcp().connect("127.0.0.1", server.port)
                    val pubOut = publisher.openWriteChannel(autoFlush = true)
                    val pubIn = publisher.openReadChannel()
                    val batch =
                        ByteArray(0) + (1..100).flatMap { command("PUBLISH", "news", "x".repeat(1024)).toList() }
                    var sent = 0
                    var dropped = false
                    val buffer = ByteArray(4096)
                    while (!dropped && sent < 200_000) {
                        pubOut.writeFully(batch)
                        sent += 100
                        var replies = ""
                        while (replies.count { it == '\n' } < 100) {
                            val n = pubIn.readAvailable(buffer)
                            check(n > 0)
                            replies += buffer.decodeToString(0, n)
                        }
                        dropped = ":0\r\n" in replies
                    }
                    assertTrue(dropped, "no drop after $sent messages of 1 KiB")
                    assertTrue(sent > 32 * 1024, "dropped after $sent KiB, before the 32 MB limit")

                    // What the kernel held is still readable; after it, the server's close.
                    var closed = false
                    val sink = ByteArray(64 * 1024)
                    while (!closed) {
                        val n =
                            try {
                                subIn.readAvailable(sink)
                            } catch (_: IOException) {
                                -1
                            }
                        closed = n < 0
                    }
                    pubOut.writeFully(command("INFO", "stats"))
                    val info = StringBuilder()
                    while ("pubsub_patterns" !in info) {
                        val n = pubIn.readAvailable(buffer)
                        check(n > 0)
                        info.append(buffer.decodeToString(0, n))
                    }
                    assertTrue("pubsub_channels:0\r\n" in info, info.toString())
                    publisher.close()
                    subscriber.close()
                }
            } finally {
                server.stop()
                server.close()
                selector.close()
            }
        }
}
