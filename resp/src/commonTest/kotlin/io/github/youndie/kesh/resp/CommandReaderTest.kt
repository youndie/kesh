package io.github.youndie.kesh.resp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CommandReaderTest {
    private fun command(vararg args: String): String =
        buildString {
            append("*${args.size}\r\n")
            args.forEach { append("$${it.encodeToByteArray().size}\r\n$it\r\n") }
        }

    private fun List<ByteArray>.strings() = map { it.decodeToString() }

    @Test
    fun `a complete command comes out whole`() {
        val reader = CommandReader()
        reader.feed(command("PING").encodeToByteArray())

        assertEquals(listOf("PING"), reader.next()!!.strings())
        assertNull(reader.next())
    }

    @Test
    fun `a command split at every byte boundary is returned only when complete`() {
        val bytes = command("SET", "user:1001:name", "Ada").encodeToByteArray()
        for (cut in 1 until bytes.size) {
            val reader = CommandReader(initialCapacity = 4)
            reader.feed(bytes, 0, cut)
            assertNull(reader.next(), "complete after only $cut of ${bytes.size} bytes")
            reader.feed(bytes, cut, bytes.size - cut)
            assertEquals(listOf("SET", "user:1001:name", "Ada"), reader.next()!!.strings())
        }
    }

    @Test
    fun `pipelined commands come out in the order they were sent`() {
        val reader = CommandReader(initialCapacity = 8)
        reader.feed((1..1000).joinToString("") { command("INCR", "k$it") }.encodeToByteArray())

        val keys = generateSequence { reader.next() }.map { it[1].decodeToString() }.toList()

        assertEquals((1..1000).map { "k$it" }, keys)
    }

    @Test
    fun `bulk strings are binary-safe`() {
        val payload = byteArrayOf(0, '\r'.code.toByte(), '\n'.code.toByte(), -1)
        val reader = CommandReader()
        reader.feed("*2\r\n$3\r\nSET\r\n$4\r\n".encodeToByteArray())
        reader.feed(payload)
        reader.feed("\r\n".encodeToByteArray())

        assertContentEquals(payload, reader.next()!![1])
    }

    @Test
    fun `an empty request is skipped`() {
        val reader = CommandReader()
        reader.feed(("*0\r\n" + command("PING")).encodeToByteArray())

        assertEquals(listOf("PING"), reader.next()!!.strings())
    }

    @Test
    fun `a malformed bulk length is refused with Redis's wording`() {
        val reader = CommandReader()
        reader.feed("*1\r\n\$x\r\nPING\r\n".encodeToByteArray())

        val error = assertFailsWith<ProtocolException> { reader.next() }
        assertEquals("Protocol error: invalid bulk length", error.message)
    }

    @Test
    fun `a malformed multibulk length is refused with Redis's wording`() {
        val reader = CommandReader()
        reader.feed("*12a\r\n".encodeToByteArray())

        val error = assertFailsWith<ProtocolException> { reader.next() }
        assertEquals("Protocol error: invalid multibulk length", error.message)
    }

    @Test
    fun `an argument that is not a bulk string is refused with Redis's wording`() {
        val reader = CommandReader()
        reader.feed("*1\r\n+PING\r\n".encodeToByteArray())

        val error = assertFailsWith<ProtocolException> { reader.next() }
        assertEquals("Protocol error: expected '$', got '+'", error.message)
    }
}
