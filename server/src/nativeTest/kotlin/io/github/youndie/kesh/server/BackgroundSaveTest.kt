package io.github.youndie.kesh.server

import io.github.youndie.kesh.server.persistence.SnapshotFile
import io.github.youndie.kesh.store.Db
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.access
import platform.posix.closedir
import platform.posix.mkdtemp
import platform.posix.opendir
import platform.posix.readdir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** `BGSAVE` through fork (B-25, research R-6), through a real socket. */
@OptIn(ExperimentalForeignApi::class)
class BackgroundSaveTest {
    private val selector = SelectorManager(Dispatchers.IO)

    private class Connection(
        val socket: Socket,
        val output: ByteWriteChannel,
        val input: ByteReadChannel,
    )

    private suspend fun connect(port: Int): Connection {
        val socket = aSocket(selector).tcp().connect("127.0.0.1", port)
        return Connection(socket, socket.openWriteChannel(autoFlush = true), socket.openReadChannel())
    }

    private fun command(vararg args: String) =
        "*${args.size}\r\n" + args.joinToString("") { "$${it.length}\r\n$it\r\n" }

    /** Sends [commands] in one write — one pipeline, executed in one read — and reads [lines] lines back. */
    private suspend fun Connection.ask(
        lines: Int,
        vararg commands: List<String>,
    ): List<String> {
        output.writeFully(commands.joinToString("") { command(*it.toTypedArray()) }.encodeToByteArray())
        val text = StringBuilder()
        val buffer = ByteArray(64 * 1024)
        while (text.split("\r\n").size - 1 < lines) {
            val n = input.readAvailable(buffer)
            check(n > 0) { "the server closed after: $text" }
            text.append(buffer.decodeToString(0, n))
        }
        return text.split("\r\n").dropLast(1)
    }

    private fun temporaryDirectory(): String = memScoped { mkdtemp("/tmp/kesh-bgsave-XXXXXX".cstr.ptr)!!.toKString() }

    /** A snapshot of [keys] keys `key:<i>` → `value:<i>`, for the server to start from. */
    private fun seed(
        directory: String,
        keys: Int,
    ) {
        val db = Db().apply { now = 1_000 }
        repeat(keys) { db.put("key:$it".encodeToByteArray(), "value:$it".encodeToByteArray()) }
        SnapshotFile(directory, "dump.kesh").save(db)
    }

    private fun filesIn(directory: String): List<String> {
        val dir = opendir(directory) ?: return emptyList()
        val names = ArrayList<String>()
        while (true) {
            val entry = readdir(dir) ?: break
            names += entry.pointed.d_name.toKString()
        }
        closedir(dir)
        return names.filter { it != "." && it != ".." }
    }

    @Test
    fun `BGSAVE writes the dataset as it was at the fork and LASTSAVE moves when it ends`() =
        runBlocking {
            val directory = temporaryDirectory()
            seed(directory, KEYS)
            val server = KeshServer(ServerConfig(host = "127.0.0.1", port = 0, httpPort = null, dir = directory))
            server.start()
            try {
                val client = connect(server.port)
                val before = client.ask(1, listOf("LASTSAVE")).single()
                // LASTSAVE is in seconds: a save that ends in the same second as the start would not move it.
                delay(1_100)
                // One pipeline, so the writes after the fork run before the child can have finished.
                val replies =
                    client.ask(
                        6,
                        listOf("BGSAVE"),
                        listOf("BGSAVE"),
                        listOf("SAVE"),
                        listOf("SET", "key:0", "changed"),
                        listOf("DEL", "key:1"),
                        listOf("SET", "after", "the fork"),
                    )
                assertEquals(
                    listOf(
                        "+Background saving started",
                        "-ERR Background save already in progress",
                        "-ERR Background save already in progress",
                        "+OK",
                        ":1",
                        "+OK",
                    ),
                    replies,
                )
                withTimeout(30.seconds) {
                    while (client.ask(1, listOf("LASTSAVE")).single() == before) delay(20)
                }
                val info = client.ask(20, listOf("INFO", "persistence")).joinToString("\n")
                assertTrue("rdb_bgsave_in_progress:0" in info, info)
                assertTrue("rdb_last_bgsave_status:ok" in info, info)
                client.socket.close()
            } finally {
                server.stop()
                server.close()
            }
            assertEquals(listOf("dump.kesh"), filesIn(directory), "a temporary file was left behind")

            val restarted = KeshServer(ServerConfig(host = "127.0.0.1", port = 0, httpPort = null, dir = directory))
            restarted.start()
            try {
                val client = connect(restarted.port)
                assertEquals(
                    listOf(":$KEYS", "$7", "value:0", ":1", ":0"),
                    client.ask(
                        5,
                        listOf("DBSIZE"),
                        listOf("GET", "key:0"),
                        listOf("EXISTS", "key:1"),
                        listOf("EXISTS", "after"),
                    ),
                )
                client.socket.close()
            } finally {
                restarted.stop()
                restarted.close()
                selector.close()
            }
        }

    @Test
    fun `a background save still running at the stop is killed and leaves nothing behind`() =
        runBlocking {
            val directory = temporaryDirectory()
            seed(directory, KEYS)
            val snapshotBefore = filesIn(directory)
            val server = KeshServer(ServerConfig(host = "127.0.0.1", port = 0, httpPort = null, dir = directory))
            server.start()
            try {
                val client = connect(server.port)
                assertEquals(listOf("+Background saving started"), client.ask(1, listOf("BGSAVE")))
                client.socket.close()
            } finally {
                server.stop()
                server.close()
                selector.close()
            }
            assertEquals(snapshotBefore, filesIn(directory), "the child's temporary file outlived the stop")
        }

    @Test
    fun `BGSAVE takes SCHEDULE and nothing else`() =
        runBlocking {
            val directory = temporaryDirectory()
            val server = KeshServer(ServerConfig(host = "127.0.0.1", port = 0, httpPort = null, dir = directory))
            server.start()
            try {
                val client = connect(server.port)
                assertEquals(
                    listOf("-ERR syntax error", "-ERR syntax error"),
                    client.ask(2, listOf("BGSAVE", "now"), listOf("BGSAVE", "SCHEDULE", "x")),
                )
                assertEquals(listOf("+Background saving started"), client.ask(1, listOf("BGSAVE", "schedule")))
                withTimeout(10.seconds) {
                    while (access("$directory/dump.kesh", 0) != 0) delay(10)
                }
                client.socket.close()
            } finally {
                server.stop()
                server.close()
                selector.close()
            }
        }

    private companion object {
        /** Enough that the child is still writing when the pipeline's next command runs. */
        const val KEYS = 300_000
    }
}
