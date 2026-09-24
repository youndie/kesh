package io.github.youndie.kesh.server.command

import io.github.youndie.kesh.resp.encode
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandDispatcherTest {
    private val dispatcher = CommandDispatcher()

    private fun reply(vararg args: String) =
        dispatcher
            .execute(
                args.map {
                    it.encodeToByteArray()
                },
            ).encode()
            .decodeToString()

    @Test
    fun `PING answers PONG whatever the case of the name`() {
        assertEquals("+PONG\r\n", reply("PING"))
        assertEquals("+PONG\r\n", reply("ping"))
    }

    @Test
    fun `PING with a message echoes it back as a bulk string`() {
        assertEquals("$5\r\nhello\r\n", reply("PING", "hello"))
    }

    @Test
    fun `PING with two arguments is an arity error in Redis's words`() {
        assertEquals("-ERR wrong number of arguments for 'ping' command\r\n", reply("PING", "a", "b"))
    }

    @Test
    fun `an unknown command quotes each argument and ends with a space as Redis does`() {
        assertEquals(
            "-ERR unknown command 'FOO', with args beginning with: 'a' 'b' \r\n",
            reply("FOO", "a", "b"),
        )
        assertEquals("-ERR unknown command 'FOO', with args beginning with: \r\n", reply("FOO"))
    }

    @Test
    fun `line breaks from the client never reach the reply's framing`() {
        assertEquals(
            "-ERR unknown command 'A B', with args beginning with: 'x y' \r\n",
            reply("A\rB", "x\ny"),
        )
    }
}
