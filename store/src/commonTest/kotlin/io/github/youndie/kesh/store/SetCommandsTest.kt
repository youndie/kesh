package io.github.youndie.kesh.store

import io.github.youndie.kesh.resp.encode
import io.github.youndie.kesh.store.commands.StoreCommands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The set scenarios of `feature-sets`. Random replies are checked for membership and count. */
class SetCommandsTest {
    private val db = Db().apply { now = 1_000_000 }
    private val commands =
        StoreCommands.all
            .associateBy { it.name }

    private fun r(vararg args: String): String =
        commands
            .getValue(args[0].lowercase())
            .run(db, args.map { it.encodeToByteArray() })
            .encode()
            .decodeToString()

    /** The bulk strings of an array reply, in order. */
    private fun items(reply: String): List<String> =
        reply
            .split("\r\n")
            .drop(1)
            .filterIndexed { i, _ -> i % 2 == 1 }
            .filter { it.isNotEmpty() }

    @Test
    fun `tags are members and counted`() {
        assertEquals(":2\r\n", r("SADD", "post:9:tags", "kotlin", "native", "kotlin"))
        assertEquals(":1\r\n", r("SISMEMBER", "post:9:tags", "kotlin"))
        assertEquals(":2\r\n", r("SCARD", "post:9:tags"))
        assertEquals("+set\r\n", r("TYPE", "post:9:tags"))
    }

    @Test
    fun `a random member stays a member`() {
        val members = (0 until 10).map { "m$it" }
        r("SADD", "s", *members.toTypedArray())
        repeat(50) {
            val picked = items(r("SRANDMEMBER", "s", "3"))
            assertEquals(3, picked.size)
            assertEquals(3, picked.toSet().size, "a positive count never repeats")
            assertTrue(picked.all { it in members }, "$picked")
        }
        assertEquals(20, items(r("SRANDMEMBER", "s", "-20")).size, "a negative count is always that long")
        assertEquals(members.toSet(), items(r("SRANDMEMBER", "s", "100")).toSet())
        assertEquals(":10\r\n", r("SCARD", "s"), "SRANDMEMBER takes nothing")
    }

    @Test
    fun `the key goes with its last member`() {
        r("SADD", "s", "a", "b")
        assertEquals(":2\r\n", r("SREM", "s", "a", "b", "c"))
        assertEquals(":0\r\n", r("EXISTS", "s"))
        r("SADD", "p", "a", "b", "c")
        val popped = items(r("SPOP", "p", "2"))
        assertEquals(2, popped.size)
        assertEquals(":1\r\n", r("SCARD", "p"))
        r("SPOP", "p")
        assertEquals(":0\r\n", r("EXISTS", "p"))
    }

    @Test
    fun `intersection union and difference`() {
        r("SADD", "a", "1", "2", "3")
        r("SADD", "b", "2", "3", "4")
        assertEquals(setOf("2", "3"), items(r("SINTER", "a", "b")).toSet())
        assertEquals(setOf("1", "2", "3", "4"), items(r("SUNION", "a", "b", "nokey")).toSet())
        assertEquals(setOf("1"), items(r("SDIFF", "a", "b")).toSet())
        assertEquals("*0\r\n", r("SINTER", "a", "nokey"))
        r("SET", "str", "x")
        assertEquals(
            "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n",
            r("SINTER", "nokey", "str"),
            "every key is type-checked, even after a missing one",
        )
    }
}
