package io.github.youndie.kesh.resp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RequestLimitsTest {
    private fun refusal(
        request: String,
        authenticated: Boolean = true,
        limits: RequestLimits = RequestLimits(),
    ): String? {
        val reader = CommandReader(limits)
        reader.feed(request.encodeToByteArray())
        return assertFailsWith<ProtocolException> { reader.next(authenticated) }.message
    }

    private fun bulk(size: Int) = "*2\r\n$3\r\nSET\r\n$$size\r\n" + "x".repeat(size) + "\r\n"

    @Test
    fun `before AUTH more than ten arguments are refused`() {
        val eleven = "*11\r\n" + "$1\r\na\r\n".repeat(11)
        assertEquals("Protocol error: unauthenticated multibulk length", refusal(eleven, authenticated = false))
    }

    @Test
    fun `before AUTH ten arguments are still accepted`() {
        val reader = CommandReader()
        reader.feed(("*10\r\n" + "$1\r\na\r\n".repeat(10)).encodeToByteArray())
        assertEquals(10, reader.next(authenticated = false)!!.size)
    }

    @Test
    fun `before AUTH a bulk over 16384 bytes is refused from its header`() {
        val header = "*2\r\n$3\r\nSET\r\n$16385\r\n"
        assertEquals("Protocol error: unauthenticated bulk length", refusal(header, authenticated = false))
    }

    @Test
    fun `before AUTH a bulk of exactly 16384 bytes is accepted`() {
        val reader = CommandReader()
        reader.feed(bulk(16384).encodeToByteArray())
        assertEquals(16384, reader.next(authenticated = false)!![1].size)
    }

    @Test
    fun `after AUTH the same bulk is accepted`() {
        val reader = CommandReader()
        reader.feed(bulk(16385).encodeToByteArray())
        assertEquals(16385, reader.next(authenticated = true)!![1].size)
    }

    @Test
    fun `a bulk over proto-max-bulk-len is refused`() {
        val limits = RequestLimits(protoMaxBulkLen = 100)
        assertEquals("Protocol error: invalid bulk length", refusal("*1\r\n$101\r\n", limits = limits))
    }

    @Test
    fun `a line that runs past the inline maximum without its ending is refused`() {
        val limits = RequestLimits(inlineMaxSize = 16)
        assertEquals("Protocol error: too big inline request", refusal("PING " + "a".repeat(20), limits = limits))
        assertEquals("Protocol error: too big mbulk count string", refusal("*" + "1".repeat(20), limits = limits))
        assertEquals("Protocol error: too big bulk count string", refusal("*1\r\n$" + "1".repeat(20), limits = limits))
    }

    @Test
    fun `a line within the inline maximum waits for its ending`() {
        val reader = CommandReader(RequestLimits(inlineMaxSize = 16))
        reader.feed("PING".encodeToByteArray())
        assertNull(reader.next())
        reader.feed("\r\n".encodeToByteArray())
        assertNotNull(reader.next())
    }

    @Test
    fun `counts are integers the way Redis writes them`() {
        assertEquals("Protocol error: invalid multibulk length", refusal("*01\r\n"))
        assertEquals("Protocol error: invalid multibulk length", refusal("*+1\r\n"))
        assertEquals("Protocol error: invalid bulk length", refusal("*1\r\n$-2\r\n"))
        assertEquals("Protocol error: invalid bulk length", refusal("*1\r\n$04\r\nPING\r\n"))
    }

    @Test
    fun `buffered counts what has not been returned yet`() {
        val reader = CommandReader()
        reader.feed("*1\r\n$4\r\nPING\r\n*1\r\n$4\r\nPI".encodeToByteArray())
        reader.next()
        assertEquals(10, reader.buffered)
    }
}
