package io.github.youndie.kesh.store

import io.github.youndie.kesh.resp.encode
import io.github.youndie.kesh.store.commands.StoreCommands
import kotlin.test.Test
import kotlin.test.assertEquals

/** The sorted set scenarios of `feature-sorted-sets` that do not need a million members. */
class SortedSetCommandsTest {
    private val db = Db().apply { now = 1_000_000 }
    private val commands = StoreCommands.all.associateBy { it.name }

    private fun r(vararg args: String): String =
        commands
            .getValue(args[0].lowercase())
            .run(db, args.map { it.encodeToByteArray() })
            .encode()
            .decodeToString()

    @Test
    fun `score ties are ordered by member bytes`() {
        assertEquals(":2\r\n", r("ZADD", "z", "1", "b", "1", "a"))
        assertEquals("*2\r\n$1\r\na\r\n$1\r\nb\r\n", r("ZRANGE", "z", "0", "-1"))
        assertEquals("+zset\r\n", r("TYPE", "z"))
    }

    @Test
    fun `a board answers its top and its ranks`() {
        repeat(300) { r("ZADD", "board", it.toString(), "player:$it") }
        assertEquals(
            "*4\r\n$10\r\nplayer:299\r\n$3\r\n299\r\n$10\r\nplayer:298\r\n$3\r\n298\r\n",
            r("ZREVRANGE", "board", "0", "1", "WITHSCORES"),
        )
        assertEquals(":0\r\n", r("ZREVRANK", "board", "player:299"))
        assertEquals(":299\r\n", r("ZRANK", "board", "player:299"))
        assertEquals(":10\r\n", r("ZCOUNT", "board", "(10", "20"))
        assertEquals("*1\r\n$9\r\nplayer:16\r\n", r("ZRANGE", "board", "(10", "20", "BYSCORE", "LIMIT", "5", "1"))
    }

    @Test
    fun `the key goes with its last member`() {
        r("ZADD", "z", "1", "a")
        assertEquals(":1\r\n", r("ZREM", "z", "a", "b"))
        assertEquals(":0\r\n", r("EXISTS", "z"))
        r("ZADD", "p", "1", "a", "2", "b")
        assertEquals("*4\r\n$1\r\nb\r\n$1\r\n2\r\n$1\r\na\r\n$1\r\n1\r\n", r("ZPOPMAX", "p", "5"))
        assertEquals(":0\r\n", r("EXISTS", "p"))
    }

    @Test
    fun `ZADD options conflict as Redis says`() {
        assertEquals(
            "-ERR XX and NX options at the same time are not compatible\r\n",
            r("ZADD", "z", "NX", "XX", "1", "a"),
        )
        assertEquals(
            "-ERR GT, LT, and/or NX options at the same time are not compatible\r\n",
            r("ZADD", "z", "GT", "LT", "1", "a"),
        )
        assertEquals(
            "-ERR INCR option supports a single increment-element pair\r\n",
            r("ZADD", "z", "INCR", "1", "a", "2", "b"),
        )
        assertEquals("-ERR value is not a valid float\r\n", r("ZADD", "z", "x", "a"))
        r("ZADD", "z", "5", "a")
        assertEquals(":0\r\n", r("ZADD", "z", "GT", "3", "a"))
        assertEquals(":1\r\n", r("ZADD", "z", "GT", "CH", "7", "a"))
        assertEquals("$2\r\n10\r\n", r("ZINCRBY", "z", "3", "a"))
        r("ZADD", "z", "inf", "i")
        assertEquals("-ERR resulting score is not a number (NaN)\r\n", r("ZINCRBY", "z", "-inf", "i"))
    }
}
