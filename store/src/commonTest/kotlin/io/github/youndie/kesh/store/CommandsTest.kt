package io.github.youndie.kesh.store

import io.github.youndie.kesh.resp.encode
import io.github.youndie.kesh.store.commands.StoreCommands
import kotlin.test.Test
import kotlin.test.assertEquals

/** The strings and keyspace scenarios, with time moved by hand instead of by sleeping. */
class CommandsTest {
    private val db = Db().apply { now = 1_000_000 }
    private val commands =
        StoreCommands.all.associateBy {
            it.name
        }

    private fun r(vararg args: String): String =
        commands
            .getValue(args[0].lowercase())
            .run(
                db,
                args.map {
                    it.encodeToByteArray()
                },
            ).encode()
            .decodeToString()

    @Test
    fun `set and get`() {
        assertEquals("+OK\r\n", r("SET", "user:1001:name", "Ada"))
        assertEquals("$3\r\nAda\r\n", r("GET", "user:1001:name"))
        assertEquals("$-1\r\n", r("GET", "missing"))
    }

    @Test
    fun `an expiring set is gone once its time has passed and not a millisecond before`() {
        r("SET", "session:42", "x", "PX", "100")
        db.now += 100
        assertEquals("$1\r\nx\r\n", r("GET", "session:42"))
        db.now += 1
        assertEquals("$-1\r\n", r("GET", "session:42"))
        assertEquals(":0\r\n", r("DBSIZE"))
    }

    @Test
    fun `SET clears an expiry unless KEEPTTL`() {
        r("SET", "k", "v", "EX", "10")
        r("SET", "k", "w", "KEEPTTL")
        assertEquals(":10\r\n", r("TTL", "k"))
        r("SET", "k", "x")
        assertEquals(":-1\r\n", r("TTL", "k"))
    }

    @Test
    fun `SET options conflict as Redis says`() {
        assertEquals("-ERR syntax error\r\n", r("SET", "k", "v", "NX", "XX"))
        assertEquals("-ERR syntax error\r\n", r("SET", "k", "v", "EX", "10", "PX", "100"))
        assertEquals("-ERR syntax error\r\n", r("SET", "k", "v", "KEEPTTL", "EX", "10"))
        assertEquals("-ERR syntax error\r\n", r("SET", "k", "v", "EX"))
        assertEquals("-ERR invalid expire time in 'set' command\r\n", r("SET", "k", "v", "EX", "0"))
        assertEquals("-ERR value is not an integer or out of range\r\n", r("SET", "k", "v", "EX", "x"))
    }

    @Test
    fun `SET NX GET on an existing key answers the old value and changes nothing`() {
        r("SET", "k", "old")
        assertEquals("$3\r\nold\r\n", r("SET", "k", "new", "NX", "GET"))
        assertEquals("$3\r\nold\r\n", r("GET", "k"))
        assertEquals("$-1\r\n", r("SET", "fresh", "v", "GET"))
    }

    @Test
    fun `counter overflow leaves the value unchanged`() {
        r("SET", "c", "9223372036854775807")
        assertEquals("-ERR increment or decrement would overflow\r\n", r("INCR", "c"))
        assertEquals("$19\r\n9223372036854775807\r\n", r("GET", "c"))
        r("SET", "d", "0")
        assertEquals("-ERR decrement would overflow\r\n", r("DECRBY", "d", "-9223372036854775808"))
        assertEquals(
            "-ERR value is not an integer or out of range\r\n",
            r("INCR", "user:1001:name")
                .also {
                    r("SET", "user:1001:name", "Ada")
                }.let { r("INCR", "user:1001:name") },
        )
    }

    @Test
    fun `INCR keeps the expiry and INCRBYFLOAT prints as Redis`() {
        r("SET", "c", "1", "EX", "10")
        assertEquals(":2\r\n", r("INCR", "c"))
        assertEquals(":10\r\n", r("TTL", "c"))
        r("SET", "f", "10.50")
        assertEquals("$4\r\n10.6\r\n", r("INCRBYFLOAT", "f", "0.1"))
        r("SET", "g", "5.0e3")
        assertEquals("$4\r\n5200\r\n", r("INCRBYFLOAT", "g", "2.0e2"))
    }

    @Test
    fun `GETRANGE and SETRANGE follow Redis's arithmetic`() {
        r("SET", "k", "This is a string")
        assertEquals("$4\r\nThis\r\n", r("GETRANGE", "k", "0", "3"))
        assertEquals("$3\r\ning\r\n", r("GETRANGE", "k", "-3", "-1"))
        assertEquals("$16\r\nThis is a string\r\n", r("GETRANGE", "k", "0", "-1"))
        assertEquals("$0\r\n\r\n", r("GETRANGE", "k", "10", "5"))
        assertEquals(":16\r\n", r("SETRANGE", "k", "10", "String"))
        assertEquals(":8\r\n", r("SETRANGE", "z", "5", "abc"))
        assertEquals("$8\r\n\u0000\u0000\u0000\u0000\u0000abc\r\n", r("GET", "z"))
    }

    @Test
    fun `TTL is -2 for a missing key and -1 without expiry and rounds to the nearest second`() {
        r("SET", "k", "v", "EX", "10")
        db.now += 9_400
        assertEquals(":1\r\n", r("TTL", "k"))
        assertEquals(":600\r\n", r("PTTL", "k"))
        assertEquals(":1\r\n", r("PERSIST", "k"))
        assertEquals(":-1\r\n", r("TTL", "k"))
        assertEquals(":-2\r\n", r("TTL", "missing"))
    }

    @Test
    fun `EXPIRE with a time already passed deletes the key and answers 1`() {
        r("SET", "k", "v")
        assertEquals(":1\r\n", r("EXPIRE", "k", "-1"))
        assertEquals(":0\r\n", r("EXISTS", "k"))
        assertEquals(":0\r\n", r("EXPIRE", "missing", "10"))
    }

    @Test
    fun `EXPIRE options`() {
        r("SET", "k", "v")
        assertEquals(":0\r\n", r("EXPIRE", "k", "10", "XX"))
        assertEquals(":1\r\n", r("EXPIRE", "k", "10", "NX"))
        assertEquals(":0\r\n", r("EXPIRE", "k", "5", "GT"))
        assertEquals(":1\r\n", r("EXPIRE", "k", "5", "LT"))
        assertEquals(
            "-ERR NX and XX, GT or LT options at the same time are not compatible\r\n",
            r("EXPIRE", "k", "5", "NX", "GT"),
        )
        assertEquals(
            "-ERR GT and LT options at the same time are not compatible\r\n",
            r("EXPIRE", "k", "5", "GT", "LT"),
        )
        assertEquals("-ERR Unsupported option FOO\r\n", r("EXPIRE", "k", "5", "FOO"))
    }

    @Test
    fun `DEL and UNLINK both count live keys only and EXISTS counts repeats`() {
        r("MSET", "a", "1", "b", "2")
        r("SET", "gone", "x", "PX", "1")
        db.now += 2
        assertEquals(":2\r\n", r("EXISTS", "a", "a"))
        assertEquals(":1\r\n", r("DEL", "a", "gone", "nope"))
        assertEquals(":1\r\n", r("UNLINK", "b"))
    }

    @Test
    fun `RENAME moves the expiry and refuses a missing key`() {
        r("SET", "a", "1", "EX", "100")
        assertEquals("+OK\r\n", r("RENAME", "a", "b"))
        assertEquals(":100\r\n", r("TTL", "b"))
        assertEquals("-ERR no such key\r\n", r("RENAME", "a", "c"))
        r("SET", "c", "3")
        assertEquals(":0\r\n", r("RENAMENX", "b", "c"))
        assertEquals("+OK\r\n", r("RENAME", "c", "c"))
    }

    @Test
    fun `KEYS matches the pattern and skips expired keys`() {
        r("MSET", "user:1", "a", "user:2", "b", "post:1", "c")
        r("SET", "user:3", "x", "PX", "1")
        db.now += 2
        val reply = r("KEYS", "user:*")
        assertEquals(2, Regex("user:[12]").findAll(reply).count())
        assertEquals("*0\r\n", r("KEYS", "nothing*"))
    }

    @Test
    fun `MSET with an odd count is an arity error and MSETNX is all or nothing`() {
        assertEquals("-ERR wrong number of arguments for 'mset' command\r\n", r("MSET", "a", "1", "b"))
        r("SET", "b", "old")
        assertEquals(":0\r\n", r("MSETNX", "a", "1", "b", "2"))
        assertEquals(":0\r\n", r("EXISTS", "a"))
    }

    @Test
    fun `FLUSHALL takes SYNC ASYNC or nothing`() {
        r("SET", "k", "v")
        assertEquals("-ERR syntax error\r\n", r("FLUSHALL", "NOW"))
        assertEquals("+OK\r\n", r("FLUSHALL", "ASYNC"))
        assertEquals(":0\r\n", r("DBSIZE"))
    }
}
