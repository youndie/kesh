package io.github.youndie.kesh.conformance

import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarnessTest {
    private fun frame(text: String) = RespFrame.read(text.byteInputStream())

    @Test
    fun `a frame is exactly one reply's bytes, nested arrays included`() {
        val input = "*2\r\n$3\r\nabc\r\n*1\r\n:7\r\n+NEXT\r\n".byteInputStream()
        val first = RespFrame.read(input)
        assertEquals("*2\r\n$3\r\nabc\r\n*1\r\n:7\r\n", first.bytes.decodeToString())
        assertEquals(2, first.elements!!.size)
        assertEquals("+NEXT\r\n", RespFrame.read(input).bytes.decodeToString())
        assertEquals(null, frame("*-1\r\n").elements)
        assertEquals("$-1\r\n", frame("$-1\r\n").bytes.decodeToString())
    }

    @Test
    fun `a reply cut short is an error, not a shorter reply`() {
        assertFailsWith<java.io.EOFException> { frame("$5\r\nab") }
    }

    @Test
    fun `unordered compares arrays as multisets and nothing else`() {
        assertTrue(
            Normaliser.UNORDERED.agree(frame("*2\r\n$1\r\na\r\n$1\r\nb\r\n"), frame("*2\r\n$1\r\nb\r\n$1\r\na\r\n")),
        )
        assertFalse(
            Normaliser.UNORDERED.agree(frame("*2\r\n$1\r\na\r\n$1\r\na\r\n"), frame("*2\r\n$1\r\na\r\n$1\r\nb\r\n")),
        )
        assertFalse(Normaliser.UNORDERED.agree(frame("+OK\r\n"), frame("+OK\r\n")))
    }

    @Test
    fun `pairs compares field and value together and not apart`() {
        val ab = "*4\r\n$1\r\na\r\n$1\r\n1\r\n$1\r\nb\r\n$1\r\n2\r\n"
        assertTrue(Normaliser.PAIRS.agree(frame(ab), frame("*4\r\n$1\r\nb\r\n$1\r\n2\r\n$1\r\na\r\n$1\r\n1\r\n")))
        val swapped = "*4\r\n$1\r\na\r\n$1\r\n2\r\n$1\r\nb\r\n$1\r\n1\r\n"
        assertTrue(Normaliser.UNORDERED.agree(frame(ab), frame(swapped)), "the weaker normaliser takes it")
        assertFalse(Normaliser.PAIRS.agree(frame(ab), frame(swapped)))
    }

    @Test
    fun `random holds both replies to the population and to the oracle's count`() {
        val abc = setOf("a", "b", "c").map { it.encodeToByteArray().toList() }.toSet()
        val ab = "*2\r\n$1\r\na\r\n$1\r\nb\r\n"
        assertTrue(Normaliser.RANDOM.agree(frame("*2\r\n$1\r\nc\r\n$1\r\na\r\n"), frame(ab), abc))
        assertFalse(Normaliser.RANDOM.agree(frame("*2\r\n$1\r\nc\r\n$1\r\nz\r\n"), frame(ab), abc), "not a member")
        assertFalse(Normaliser.RANDOM.agree(frame("*1\r\n$1\r\nc\r\n"), frame(ab), abc), "not the count")
        assertFalse(Normaliser.RANDOM.agree(frame("*2\r\n$1\r\nc\r\n$1\r\nc\r\n"), frame(ab), abc), "a repeat")
        assertTrue(
            Normaliser.RANDOM.agree(frame("*2\r\n$1\r\nc\r\n$1\r\nc\r\n"), frame("*2\r\n$1\r\na\r\n$1\r\na\r\n"), abc),
            "repeats allowed where the oracle repeats",
        )
        assertFalse(
            Normaliser.RANDOM.agree(frame(ab), frame("*2\r\n$1\r\na\r\n$1\r\nz\r\n"), abc),
            "the oracle is held too",
        )
        assertTrue(Normaliser.RANDOM.agree(frame("$1\r\nb\r\n"), frame("$1\r\nc\r\n"), abc))
        assertTrue(Normaliser.RANDOM.agree(frame("$-1\r\n"), frame("$-1\r\n"), abc))
        assertFalse(Normaliser.RANDOM.agree(frame("$1\r\nb\r\n"), frame("$-1\r\n"), abc))
    }

    @Test
    fun `info compares the named fields and holds the oracle to having them`() {
        fun report(vararg lines: String): RespFrame {
            val text = lines.joinToString("\r\n", postfix = "\r\n")
            return frame("$${text.length}\r\n$text\r\n")
        }
        val redis = report("# Stats", "expired_keys:0", "evicted_keys:3", "evicted_clients:0")
        val fields = listOf("evicted_keys")
        assertTrue(Normaliser.INFO.agree(report("# Stats", "evicted_keys:3"), redis, fields = fields))
        assertFalse(Normaliser.INFO.agree(report("# Stats", "evicted_keys:2"), redis, fields = fields))
        assertFalse(Normaliser.INFO.agree(report("# Stats"), redis, fields = fields), "missing in kesh")
        assertFalse(
            Normaliser.INFO.agree(report("evicted_key:3"), report("evicted_key:3"), fields = fields),
            "a field the oracle lacks is a broken line, not agreement",
        )
    }

    @Test
    fun `fields holds kesh's sections and names to Redis's and ignores values`() {
        fun report(vararg lines: String): RespFrame {
            val text = lines.joinToString("\r\n", postfix = "\r\n")
            return frame("$${text.length}\r\n$text\r\n")
        }
        val redis =
            report(
                "# Server",
                "redis_version:7.2.16",
                "redis_mode:standalone",
                "",
                "# Clients",
                "connected_clients:1",
                "maxclients:10000",
            )
        val named = listOf("connected_clients")
        assertTrue(
            Normaliser.FIELDS.agree(
                report("# Server", "redis_version:7.2.0", "", "# Clients", "connected_clients:5"),
                redis,
                fields = named,
            ),
            "fewer fields, other values",
        )
        assertFalse(
            Normaliser.FIELDS.agree(report("# Server", "kesh_thing:1"), redis, fields = emptyList()),
            "a field Redis lacks",
        )
        assertFalse(
            Normaliser.FIELDS.agree(report("# Server", "redis_mode:x", "redis_version:y"), redis, fields = emptyList()),
            "Redis's order",
        )
        assertFalse(
            Normaliser.FIELDS.agree(report("# Clients", "redis_mode:x"), redis, fields = emptyList()),
            "another section",
        )
        assertFalse(Normaliser.FIELDS.agree(report("# Server"), redis, fields = named), "a named field kesh left out")
        assertFalse(
            Normaliser.FIELDS.agree(report("# Clients", "connected_clients:1"), report("# Clients"), fields = named),
        )
    }

    @Test
    fun `shape ignores content and keeps types, lengths and nulls`() {
        assertTrue(Normaliser.SHAPE.agree(frame("*2\r\n$4\r\nkesh\r\n:1\r\n"), frame("*2\r\n$5\r\nredis\r\n:9\r\n")))
        assertFalse(Normaliser.SHAPE.agree(frame("*2\r\n$4\r\nkesh\r\n:1\r\n"), frame("*2\r\n$4\r\nkesh\r\n+1\r\n")))
        assertFalse(Normaliser.SHAPE.agree(frame("*1\r\n:1\r\n"), frame("*2\r\n:1\r\n:1\r\n")))
        assertFalse(Normaliser.SHAPE.agree(frame("$-1\r\n"), frame("$0\r\n\r\n")))
    }

    @Test
    fun `a script names its normalisers per line and its password once`() {
        val script =
            Script.parse(
                "s",
                """
                # requires: password
                # a comment
                PING "two words"
                [unordered] SMEMBERS s
                [closes] QUIT
                [raw] *1\r\n$1\r\nx\x21
                """.trimIndent(),
            )
        assertTrue(script.requiresPassword)
        assertEquals(
            listOf(
                Normaliser.EXACT,
                Normaliser.UNORDERED,
                Normaliser.EXACT,
                Normaliser.EXACT,
            ),
            script.steps.map {
                it.normaliser
            },
        )
        assertEquals(
            listOf(
                Script.Kind.COMMAND,
                Script.Kind.COMMAND,
                Script.Kind.CLOSES,
                Script.Kind.RAW,
            ),
            script.steps.map {
                it.kind
            },
        )
        assertEquals("*2\r\n$4\r\nPING\r\n$9\r\ntwo words\r\n", script.steps[0].bytes.decodeToString())
        assertEquals("*1\r\n$1\r\nx!", script.steps[3].bytes.decodeToString())
    }

    @Test
    fun `an unknown tag is a broken script, not a silent exact comparison`() {
        assertFailsWith<IllegalStateException> { Script.parse("s", "[sorted] SMEMBERS s") }
    }

    // --- the harness's own positive control: two servers one byte apart must disagree -----------

    private val servers = mutableListOf<ServerSocket>()

    @AfterTest
    fun stop() = servers.forEach { it.close() }

    /** Answers every request with [reply], and closes after a request that starts with `*1\r\n$4\r\nQUIT`. */
    private fun fake(reply: String): Endpoint {
        val server = ServerSocket(0).also { servers += it }
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    client.use {
                        val input = it.getInputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            it.getOutputStream().write(reply.encodeToByteArray())
                            if (buffer.decodeToString(0, n).startsWith("*1\r\n$4\r\nQUIT")) break
                        }
                    }
                }
            }
        }
        return Endpoint("127.0.0.1", server.localPort)
    }

    @Test
    fun `identical servers agree`() {
        val outcomes = Runner(fake("+PONG\r\n"), fake("+PONG\r\n")).run(Script.parse("s", "PING\nPING"))
        assertTrue(outcomes.all { it.agree })
    }

    @Test
    fun `servers one byte apart disagree, and the report points at the byte`() {
        val outcomes =
            Runner(
                fake("-ERR DB index out of range\r\n"),
                fake("-ERR DB index is out of range\r\n"),
            ).run(Script.parse("s", "SELECT 1"))
        assertFalse(outcomes.single().agree)
        assertTrue("first difference at byte 14" in Runner.describe(outcomes.single()))
    }

    @Test
    fun `a server that closes when the other does not disagrees`() {
        val outcomes =
            Runner(
                fake("+OK\r\n"),
                fake("+OK\r\n"),
                quietAfter = 100,
            ).run(Script.parse("s", "[closes] PING"))
        assertFalse(
            outcomes.single().agree,
            "neither closed after PING, which [closes] requires of both — and they agree on that",
        )
    }
}
