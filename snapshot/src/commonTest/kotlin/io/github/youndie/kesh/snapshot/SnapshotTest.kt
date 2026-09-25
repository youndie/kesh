package io.github.youndie.kesh.snapshot

import io.github.youndie.kesh.resp.encode
import io.github.youndie.kesh.store.Db
import io.github.youndie.kesh.store.commands.StoreCommands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The format: every kind round-trips, expired keys stay behind, and a damaged file is refused whole. */
class SnapshotTest {
    private val commands = StoreCommands.all.associateBy { it.name }

    private fun Db.r(vararg args: String): String =
        commands
            .getValue(args[0].lowercase())
            .run(this, args.map { it.encodeToByteArray() })
            .encode()
            .decodeToString()

    private fun save(db: Db): ByteArray {
        val out = ArrayList<Byte>()
        Snapshot.write(db) { bytes, offset, length -> for (i in offset until offset + length) out.add(bytes[i]) }
        return out.toByteArray()
    }

    private fun load(
        bytes: ByteArray,
        now: Long,
    ): Db {
        val db = Db(seed = 2).apply { this.now = now }
        var at = 0
        Snapshot.read(db) { buffer, offset, length ->
            if (at == bytes.size) {
                -1
            } else {
                val n = minOf(length, bytes.size - at, 7_777)
                bytes.copyInto(buffer, offset, at, at + n)
                at += n
                n
            }
        }
        return db
    }

    private fun sample(): Db =
        Db(seed = 1).apply {
            now = 1_000_000
            r("SET", "s", "value")
            r("SET", "ttl", "v", "PX", "5000")
            r("SET", "gone", "v", "PX", "10")
            r("HSET", "h", "a", "1", "b", "2")
            r("HSET", "bigh", *(0 until 600).flatMap { listOf("f$it", "v$it") }.toTypedArray())
            r("RPUSH", "l", *(0 until 3000).map { "item$it" }.toTypedArray())
            r("SADD", "set", "x", "y", "z")
            r("SADD", "bigset", *(0 until 500).map { "m$it" }.toTypedArray())
            r("ZADD", "z", "1.5", "a", "-0", "b", "inf", "c")
            r("ZADD", "bigz", *(0 until 300).flatMap { listOf("${it * 0.25}", "p$it") }.toTypedArray())
        }

    @Test
    fun `every kind comes back as it was`() {
        val before = sample()
        before.now += 20 // "gone" has expired and nothing has looked
        val after = load(save(before), before.now)
        assertEquals(before.size - 1, after.size, "every live key, and not the expired one")
        for (probe in listOf(
            listOf("GET", "s"),
            listOf("PTTL", "ttl"),
            listOf("EXISTS", "gone"),
            listOf("HGETALL", "h"),
            listOf("HLEN", "bigh"),
            listOf("HGET", "bigh", "f599"),
            listOf("LRANGE", "l", "0", "-1"),
            listOf("SCARD", "bigset"),
            listOf("SISMEMBER", "bigset", "m499"),
            listOf("ZRANGE", "z", "0", "-1", "WITHSCORES"),
            listOf("ZRANGE", "bigz", "0", "-1", "WITHSCORES"),
            listOf("TYPE", "set"),
        )) {
            assertEquals(before.r(*probe.toTypedArray()), after.r(*probe.toTypedArray()), probe.joinToString(" "))
        }
        assertEquals(after.recount(), after.usedMemory)
        assertEquals(1, after.expires.size)
    }

    @Test
    fun `a key that expires between save and load is not loaded`() {
        val before = sample()
        val bytes = save(before)
        val after = load(bytes, before.now + 6_000)
        assertEquals(0, after.expires.size)
        assertEquals(":0\r\n", after.r("EXISTS", "ttl"))
    }

    @Test
    fun `a file cut anywhere is refused`() {
        val bytes = save(sample())
        for (cut in listOf(0, 3, 8, 20, bytes.size / 3, bytes.size / 2, bytes.size - 5, bytes.size - 1)) {
            assertFailsWith<SnapshotException>("cut at $cut of ${bytes.size}") { load(bytes.copyOf(cut), 1_000_000) }
        }
    }

    @Test
    fun `a single damaged byte is refused`() {
        val bytes = save(sample())
        var refused = 0
        for (at in listOf(30, 200, bytes.size / 2, bytes.size - 10)) {
            val damaged = bytes.copyOf().also { it[at] = (it[at].toInt() xor 0x20).toByte() }
            val failed = runCatching { load(damaged, 1_000_000) }.exceptionOrNull()
            if (failed != null) refused++
            assertTrue(failed == null || failed is SnapshotException, "${failed?.let { it::class }} at $at")
        }
        assertEquals(4, refused)
    }

    @Test
    fun `an empty dataset round-trips`() {
        val after = load(save(Db()), 0)
        assertEquals(0, after.size)
    }
}
