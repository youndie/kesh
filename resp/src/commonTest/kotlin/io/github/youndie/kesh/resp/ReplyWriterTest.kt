package io.github.youndie.kesh.resp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReplyWriterTest {
    private fun Reply.wire() = encode().decodeToString()

    @Test
    fun `each reply kind is framed as RESP2 frames it`() {
        assertEquals("+PONG\r\n", Reply.PONG.wire())
        assertEquals("-ERR syntax error\r\n", Reply.Error("ERR syntax error").wire())
        assertEquals(":-42\r\n", Reply.Integer(-42).wire())
        assertEquals("$3\r\nAda\r\n", Reply.Bulk("Ada".encodeToByteArray()).wire())
        assertEquals("$-1\r\n", Reply.NULL_BULK.wire())
        assertEquals("*-1\r\n", Reply.Multi(null).wire())
        assertEquals(
            "*2\r\n:1\r\n$1\r\nx\r\n",
            Reply.Multi(listOf(Reply.Integer(1), Reply.Bulk("x".encodeToByteArray()))).wire(),
        )
    }

    @Test
    fun `a batch of replies is one buffer in order`() {
        val writer = ReplyWriter(initialCapacity = 2)
        (1..1000L).forEach { writer.write(Reply.Integer(it)) }

        assertEquals((1..1000).joinToString("") { ":$it\r\n" }, writer.toByteArray().decodeToString())
    }

    @Test
    fun `a line break in a text reply is refused rather than sent`() {
        assertFailsWith<IllegalArgumentException> { Reply.Error("ERR a\r\nb") }
    }
}
