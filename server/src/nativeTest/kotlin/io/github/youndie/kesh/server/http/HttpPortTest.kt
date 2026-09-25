package io.github.youndie.kesh.server.http

import io.github.youndie.kesh.server.KeshServer
import io.github.youndie.kesh.server.ServerConfig
import io.github.youndie.kesh.server.persistence.SnapshotFile
import io.github.youndie.kesh.store.Db
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.posix.mkdtemp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The HTTP port through a real socket, as a kubelet and a Prometheus scrape reach it (B-15). */
class HttpPortTest {
    private val selector = SelectorManager(Dispatchers.IO)

    private class Answer(
        val status: Int,
        val headers: String,
        val body: String,
    )

    private suspend fun request(
        port: Int,
        text: String,
    ): Answer {
        val socket = aSocket(selector).tcp().connect("127.0.0.1", port)
        try {
            socket.openWriteChannel(autoFlush = true).writeFully(text.encodeToByteArray())
            val input = socket.openReadChannel()
            val out = StringBuilder()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.readAvailable(buffer)
                if (n < 0) break
                out.append(buffer.decodeToString(0, n))
            }
            val raw = out.toString()
            val head = raw.substringBefore("\r\n\r\n")
            return Answer(head.substringBefore("\r\n").split(' ')[1].toInt(), head, raw.substringAfter("\r\n\r\n"))
        } finally {
            socket.close()
        }
    }

    private suspend fun get(
        port: Int,
        path: String,
    ) = request(port, "GET $path HTTP/1.1\r\nHost: kesh\r\nUser-Agent: test\r\n\r\n")

    private suspend fun resp(
        port: Int,
        vararg commands: List<String>,
    ) {
        val socket = aSocket(selector).tcp().connect("127.0.0.1", port)
        val output = socket.openWriteChannel(autoFlush = true)
        val input = socket.openReadChannel()
        for (args in commands) {
            output.writeFully(
                ("*${args.size}\r\n" + args.joinToString("") { "$${it.length}\r\n$it\r\n" }).encodeToByteArray(),
            )
            val buffer = ByteArray(4096)
            check(input.readAvailable(buffer) > 0)
        }
        socket.close()
    }

    private fun withServer(
        config: ServerConfig = ServerConfig(host = "127.0.0.1", port = 0, httpPort = 0),
        block: suspend (KeshServer) -> Unit,
    ) = runBlocking {
        val server = KeshServer(config)
        server.start()
        try {
            withTimeout(20.seconds) { block(server) }
        } finally {
            server.stop()
            server.close()
            selector.close()
        }
    }

    @Test
    fun `the three probes answer 200 once started and readiness falls when the announce stage runs`() =
        withServer { server ->
            val port = server.httpPort!!
            assertEquals(200, get(port, "/health/live").status)
            assertEquals(200, get(port, "/health/started").status)
            val ready = get(port, "/health/ready")
            assertEquals(200, ready.status, ready.body)
            assertTrue("Connection: close" in ready.headers)
            server.readiness.beginShutdown()
            val draining = get(port, "/health/ready")
            assertEquals(503, draining.status)
            assertTrue("shutting down" in draining.body, draining.body)
            assertEquals(200, get(port, "/health/live").status, "draining is not wedged")
            assertEquals(200, get(port, "/health/started").status, "started is a latch")
        }

    @Test
    fun `an unknown path is 404 and anything but GET is 405 and garbage is 400`() =
        withServer { server ->
            val port = server.httpPort!!
            assertEquals(404, get(port, "/nothing").status)
            assertEquals(405, request(port, "POST /metrics HTTP/1.1\r\nContent-Length: 0\r\n\r\n").status)
            assertEquals(400, request(port, "hello\r\n\r\n").status)
            assertEquals(200, get(port, "/health/live?verbose=1").status, "a query string is not part of the path")
        }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun `readiness is false while the snapshot loads and true after it`() =
        runBlocking {
            val dir = memScoped { mkdtemp("/tmp/kesh-ready-XXXXXX".cstr.ptr)!!.toKString() }
            val db = Db().apply { now = 1_000 }
            repeat(300_000) { db.put("key:$it".encodeToByteArray(), "value:$it".encodeToByteArray()) }
            SnapshotFile(dir, "dump.kesh").save(db)

            val server = KeshServer(ServerConfig(host = "127.0.0.1", port = 0, httpPort = 0, dir = dir))
            val answers = ArrayList<Pair<Int, String>>()
            val probing =
                async(Dispatchers.IO) {
                    while (server.httpPort == null) delay(1)
                    val port = server.httpPort!!
                    while (true) {
                        val answer = get(port, "/health/ready")
                        answers += answer.status to answer.body
                        if (answer.status == 200) break
                        delay(20)
                    }
                }
            try {
                withContext(Dispatchers.IO) { server.start() }
                withTimeout(20.seconds) { probing.await() }
                val during = answers.toList()
                assertTrue(during.first().first == 503, "the first probe came before the load ended: $during")
                assertTrue("snapshot" in during.first().second, during.first().second)
                assertEquals(200, during.last().first)
                assertTrue(during.dropLast(1).all { it.first == 503 }, "never ready, then not ready again: $during")
            } finally {
                probing.cancel()
                server.stop()
                server.close()
                selector.close()
            }
        }

    @Test
    fun `metrics parse as Prometheus text and carry memory threads and the command histogram`() =
        withServer { server ->
            resp(
                server.port,
                listOf("SET", "a", "1"),
                listOf("GET", "a"),
                listOf("GET", "b"),
                listOf("CONFIG", "GET", "maxmemory"),
            )
            val answer = get(server.httpPort!!, "/metrics")
            assertEquals(200, answer.status)
            assertTrue("Content-Type: text/plain; version=0.0.4" in answer.headers, answer.headers)
            val families = PrometheusText.parse(answer.body)
            for (name in listOf("kesh_used_memory_bytes", "kesh_resident_memory_bytes", "kesh_threads", "kesh_keys")) {
                assertTrue(name in families, "$name missing from ${families.keys}")
            }
            assertTrue(families.getValue("kesh_resident_memory_bytes").single().value > 1_000_000)
            assertTrue(families.getValue("kesh_threads").single().value >= 2)
            assertEquals(1.0, families.getValue("kesh_keys").single().value)
            val counts =
                families
                    .getValue("kesh_command_duration_seconds")
                    .filter { it.name.endsWith("_count") }
                    .associate { it.labels.getValue("command") to it.value }
            assertEquals(2.0, counts["get"], "$counts")
            assertEquals(1.0, counts["set"], "$counts")
            assertEquals(1.0, counts["config|get"], "$counts")
        }
}
