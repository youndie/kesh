package io.github.youndie.kesh.store

import io.github.youndie.kesh.resp.encode
import io.github.youndie.kesh.store.commands.StoreCommands
import kotlin.test.Test
import kotlin.test.assertEquals

/** The hash scenarios of `feature-hashes`, and the wrong-type one `feature-strings` waited for. */
class HashCommandsTest {
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
    fun `a profile counts its visits`() {
        assertEquals(":3\r\n", r("HSET", "user:1001", "name", "Ada", "plan", "pro", "visits", "0"))
        assertEquals(":1\r\n", r("HINCRBY", "user:1001", "visits", "1"))
        assertEquals(
            "*6\r\n$4\r\nname\r\n$3\r\nAda\r\n$4\r\nplan\r\n$3\r\npro\r\n$6\r\nvisits\r\n$1\r\n1\r\n",
            r("HGETALL", "user:1001"),
        )
        assertEquals("+hash\r\n", r("TYPE", "user:1001"))
    }

    @Test
    fun `the key goes with its last field`() {
        r("HSET", "h", "only", "1")
        assertEquals(":1\r\n", r("HDEL", "h", "only", "only"))
        assertEquals(":0\r\n", r("EXISTS", "h"))
        assertEquals("*0\r\n", r("HGETALL", "h"))
    }

    @Test
    fun `a string command on a hash answers WRONGTYPE`() {
        r("HSET", "h", "f", "v")
        assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n", r("GET", "h"))
        r("SET", "s", "x")
        assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n", r("HGET", "s", "f"))
    }

    @Test
    fun `counters refuse what is not a number and what would overflow`() {
        r("HSET", "h", "text", "abc", "big", "9223372036854775807", "f", "1.5")
        assertEquals("-ERR hash value is not an integer\r\n", r("HINCRBY", "h", "text", "1"))
        assertEquals("-ERR increment or decrement would overflow\r\n", r("HINCRBY", "h", "big", "1"))
        assertEquals("-ERR hash value is not a float\r\n", r("HINCRBYFLOAT", "h", "text", "1"))
        assertEquals("-ERR value is NaN or Infinity\r\n", r("HINCRBYFLOAT", "h", "f", "inf"))
        assertEquals("$3\r\n1.6\r\n", r("HINCRBYFLOAT", "h", "f", "0.1"))
        assertEquals("-ERR wrong number of arguments for 'hset' command\r\n", r("HSET", "h", "a", "1", "b"))
    }
}
