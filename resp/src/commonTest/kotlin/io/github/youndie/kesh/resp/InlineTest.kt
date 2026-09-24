package io.github.youndie.kesh.resp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class InlineTest {
    private fun split(line: String) = splitInlineArguments(line.encodeToByteArray())?.map { it.decodeToString() }

    @Test
    fun `words are separated by any run of whitespace`() {
        assertEquals(listOf("SET", "k", "v"), split("  SET \t k   v  "))
    }

    @Test
    fun `double quotes keep spaces and understand escapes`() {
        assertEquals(listOf("SET", "k", "a b\n\"c\""), split("SET k \"a b\\n\\\"c\\\"\""))
        assertContentEquals(byteArrayOf(0x41, 0x7f), splitInlineArguments("\"\\x41\\x7f\"".encodeToByteArray())!![0])
    }

    @Test
    fun `single quotes understand only an escaped single quote`() {
        assertEquals(listOf("it's", "a\\nb"), split("'it\\'s' 'a\\nb'"))
    }

    @Test
    fun `a quote may open in the middle of a word`() {
        assertEquals(listOf("abcd"), split("ab\"cd\""))
    }

    @Test
    fun `unbalanced or glued quotes are refused`() {
        assertNull(split("SET k \"v"))
        assertNull(split("SET k 'v"))
        assertNull(split("SET k \"v\"x"))
    }

    @Test
    fun `an empty line has no arguments`() {
        assertEquals(emptyList(), split("   "))
    }

    @Test
    fun `an inline command comes out of the reader and a blank line is skipped`() {
        val reader = CommandReader()
        reader.feed("\r\nEXISTS somekey\r\nPING\n".encodeToByteArray())

        assertEquals(listOf("EXISTS", "somekey"), reader.next()!!.map { it.decodeToString() })
        assertEquals(listOf("PING"), reader.next()!!.map { it.decodeToString() })
        assertNull(reader.next())
    }

    @Test
    fun `unbalanced quotes in an inline command are refused with Redis's wording`() {
        val reader = CommandReader()
        reader.feed("SET k \"v\r\n".encodeToByteArray())
        assertEquals(
            "Protocol error: unbalanced quotes in request",
            assertFailsWith<ProtocolException> {
                reader.next()
            }.message,
        )
    }
}
