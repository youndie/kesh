package io.github.youndie.kesh.store

import io.github.youndie.kesh.resp.encode
import io.github.youndie.kesh.store.commands.StoreCommands
import io.github.youndie.kesh.store.keyspace.Keyspace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `SCAN`'s guarantee on the table, and the command's options. */
class ScanTest {
    @Test
    fun `every key present for the whole iteration is seen across a resize`() {
        val outcome = ScanCompleteness.run(keys = 100_000, added = 60_000, deleted = 20_000)
        assertTrue(outcome.resized, "the table must resize during the iteration")
        assertEquals(emptyList(), outcome.missed.take(5), "${outcome.missed.size} stable keys never seen")
    }

    private val db = Db().apply { now = 1_000_000 }
    private val commands = StoreCommands.all.associateBy { it.name }

    private fun r(vararg args: String): String =
        commands
            .getValue(args[0].lowercase())
            .handler(db, args.map { it.encodeToByteArray() })
            .encode()
            .decodeToString()

    @Test
    fun `a small collection comes back whole with cursor zero`() {
        r("HSET", "h", "a", "1", "b", "2")
        assertEquals(
            "*2\r\n$1\r\n0\r\n*4\r\n$1\r\na\r\n$1\r\n1\r\n$1\r\nb\r\n$1\r\n2\r\n",
            r("HSCAN", "h", "12345", "COUNT", "1"),
        )
        assertEquals("*2\r\n$1\r\n0\r\n*0\r\n", r("SSCAN", "nokey", "0"))
    }

    @Test
    fun `options and refusals are Redis's`() {
        assertEquals("-ERR invalid cursor\r\n", r("SCAN", "x"))
        assertEquals("-ERR invalid cursor\r\n", r("SCAN", " 1"))
        assertEquals("-ERR syntax error\r\n", r("SCAN", "0", "COUNT", "0"))
        r("SET", "k", "v")
        assertEquals("*2\r\n$1\r\n0\r\n*0\r\n", r("SCAN", "0", "TYPE", "blob"), "7.2 refuses no type name")
        assertEquals(
            "*2\r\n$1\r\n0\r\n*0\r\n",
            r("HSCAN", "h", "0", "TYPE", "hash"),
            "a missing key before the options",
        )
        r("HSET", "h", "a", "1")
        assertEquals("-ERR syntax error\r\n", r("HSCAN", "h", "0", "TYPE", "hash"), "TYPE is SCAN's only")
    }

    @Test
    fun `an expired key is left out and deleted`() {
        r("SET", "gone", "x", "PX", "10")
        r("SET", "here", "x")
        db.now += 11
        assertEquals(":2\r\n", r("DBSIZE"), "not reclaimed until something looks")
        assertEquals("*2\r\n$1\r\n0\r\n*1\r\n$4\r\nhere\r\n", r("SCAN", "0"))
        assertEquals(":1\r\n", r("DBSIZE"), "SCAN deleted what it found expired")
    }

    @Test
    fun `a full SCAN with TYPE and MATCH returns exactly what matches`() {
        repeat(500) { r("SET", "s:$it", "v") }
        repeat(50) { r("HSET", "h:$it", "f", "v") }
        val seen = HashSet<String>()
        var cursor = "0"
        do {
            val reply = r("SCAN", cursor, "COUNT", "37", "MATCH", "*:1*", "TYPE", "hash").split("\r\n")
            cursor = reply[2]
            reply
                .drop(4)
                .filterIndexed { i, _ -> i % 2 == 1 }
                .filter { it.isNotEmpty() }
                .forEach { seen.add(it) }
        } while (cursor != "0")
        assertEquals((0 until 50).map { "h:$it" }.filter { it.startsWith("h:1") }.toSet(), seen)
    }
}

/** The completeness scenario, shared by the common test and the million-key one on linuxX64. */
object ScanCompleteness {
    class Outcome(
        val resized: Boolean,
        val missed: List<String>,
    )

    /**
     * [keys] keys, then a scan in steps of about a hundred entries; between steps, [added] new keys go
     * in and [deleted] of the first keys come out, spread over the first 500 steps — enough growth
     * to resize the table mid-iteration. Every key neither deleted nor added must be seen.
     */
    fun run(
        keys: Int,
        added: Int,
        deleted: Int,
    ): Outcome {
        val keyspace = Keyspace(seed = 5)
        repeat(keys) { keyspace.put("key:$it".encodeToByteArray(), it) }
        val seen = HashSet<String>()
        var resized = false
        var cursor = 0L
        var nextAdded = 0
        var nextDeleted = 0
        do {
            var sampled = 0
            do {
                cursor =
                    keyspace.scan(cursor) {
                        seen.add(it.key.decodeToString())
                        sampled++
                    }
            } while (cursor != 0L && sampled < 100)
            // Mutations between calls, as other clients would make them.
            repeat(added / 500 + 1) {
                if (nextAdded < added) keyspace.put("new:${nextAdded++}".encodeToByteArray(), 0)
                if (keyspace.isRehashing) resized = true
            }
            repeat(deleted / 500 + 1) {
                if (nextDeleted < deleted) keyspace.remove("key:${nextDeleted++}".encodeToByteArray())
            }
        } while (cursor != 0L)
        val missed = (deleted until keys).map { "key:$it" }.filter { it !in seen }
        return Outcome(resized, missed)
    }
}
