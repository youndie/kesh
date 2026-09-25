package io.github.youndie.kesh.store

import io.github.youndie.kesh.resp.encode
import io.github.youndie.kesh.store.commands.StoreCommands
import kotlin.test.Test
import kotlin.test.assertEquals

/** The list scenarios of `feature-lists`. */
class ListCommandsTest {
    private val db = Db().apply { now = 1_000_000 }
    private val commands =
        StoreCommands.all.associateBy {
            it.name
        }

    private fun r(vararg args: String): String =
        commands
            .getValue(args[0].lowercase())
            .run(db, args.map { it.encodeToByteArray() })
            .encode()
            .decodeToString()

    @Test
    fun `a feed keeps its most recent items`() {
        assertEquals(":3\r\n", r("LPUSH", "feed:7", "a", "b", "c"))
        assertEquals("+OK\r\n", r("LTRIM", "feed:7", "0", "1"))
        assertEquals("*2\r\n$1\r\nc\r\n$1\r\nb\r\n", r("LRANGE", "feed:7", "0", "-1"))
        assertEquals("+list\r\n", r("TYPE", "feed:7"))
    }

    @Test
    fun `the key goes with its last item`() {
        r("RPUSH", "q", "x")
        assertEquals("$1\r\nx\r\n", r("RPOP", "q"))
        assertEquals(":0\r\n", r("EXISTS", "q"))
        r("RPUSH", "t", "a", "b")
        assertEquals("+OK\r\n", r("LTRIM", "t", "5", "10"))
        assertEquals(":0\r\n", r("EXISTS", "t"))
    }

    @Test
    fun `pops with a count come from their own end`() {
        r("RPUSH", "l", "1", "2", "3", "4")
        assertEquals("*2\r\n$1\r\n4\r\n$1\r\n3\r\n", r("RPOP", "l", "2"))
        assertEquals("*2\r\n$1\r\n1\r\n$1\r\n2\r\n", r("LPOP", "l", "5"))
        assertEquals("*-1\r\n", r("LPOP", "l", "1"))
        assertEquals("$-1\r\n", r("LPOP", "l"))
        assertEquals("-ERR value is out of range, must be positive\r\n", r("LPOP", "l", "-1"))
    }

    @Test
    fun `indexes count from the tail when negative`() {
        r("RPUSH", "l", "a", "b", "c")
        assertEquals("$1\r\nc\r\n", r("LINDEX", "l", "-1"))
        assertEquals("$-1\r\n", r("LINDEX", "l", "3"))
        assertEquals("+OK\r\n", r("LSET", "l", "-3", "z"))
        assertEquals("-ERR index out of range\r\n", r("LSET", "l", "3", "z"))
        assertEquals("-ERR no such key\r\n", r("LSET", "nokey", "0", "z"))
        assertEquals("*3\r\n$1\r\nz\r\n$1\r\nb\r\n$1\r\nc\r\n", r("LRANGE", "l", "-100", "100"))
    }
}
