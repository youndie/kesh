package io.github.youndie.kesh.store

import io.github.youndie.kesh.store.commands.StoreCommands
import io.github.youndie.kesh.store.hashes.HashValue
import io.github.youndie.kesh.store.lists.ListValue
import io.github.youndie.kesh.store.memory.MemoryModel
import io.github.youndie.kesh.store.sets.SetValue
import io.github.youndie.kesh.store.zsets.ZSetValue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `used_memory` held against a second count: after every command of a random workload over all five
 * types, the running total must equal the sum taken from scratch, and every collection's running
 * total its own recount.
 */
class MemoryAccountingTest {
    private val db = Db(seed = 9).apply { now = 1_000_000 }
    private val commands = StoreCommands.all.associateBy { it.name }

    private fun r(vararg args: String) =
        commands.getValue(args[0].lowercase()).run(db, args.map { it.encodeToByteArray() })

    private fun check(step: Int) {
        assertEquals(db.recount(), db.usedMemory, "used_memory drifted from its recount at step $step")
        // The expires index holds exactly the keys that have an expiry (B-13).
        var volatile = 0
        db.keyspace.forEach { entry ->
            if (entry.expireAt != io.github.youndie.kesh.store.keyspace.Entry.NO_EXPIRY) {
                volatile++
                assertTrue(
                    db.expires.get(entry.key)?.value === entry,
                    "${entry.key.decodeToString()} missing from expires",
                )
            }
        }
        assertEquals(volatile, db.expires.size, "expires holds keys without an expiry at step $step")
        db.keyspace.forEach { entry ->
            when (val v = entry.value) {
                is HashValue -> assertEquals(v.recountBytes(), v.estimatedBytes, "hash ${entry.key.decodeToString()}")
                is ListValue -> assertEquals(v.recountBytes(), v.estimatedBytes, "list ${entry.key.decodeToString()}")
                is SetValue -> assertEquals(v.recountBytes(), v.estimatedBytes, "set ${entry.key.decodeToString()}")
                is ZSetValue -> assertEquals(v.recountBytes(), v.estimatedBytes, "zset ${entry.key.decodeToString()}")
            }
        }
    }

    @Test
    fun `the running total equals a recount through a random workload`() {
        val random = Random(4)

        fun key() = "k" + random.nextInt(40)

        fun member() = "m" + random.nextInt(300)

        fun value() = "v".repeat(random.nextInt(1, 90))
        repeat(20_000) { step ->
            when (random.nextInt(26)) {
                0 -> r("SET", key(), value())
                1 -> r("APPEND", key(), value())
                2 -> r("DEL", key(), key())
                3 -> r("HSET", "h" + key(), member(), value(), member(), value())
                4 -> r("HDEL", "h" + key(), member(), member())
                5 -> r("HINCRBY", "h" + key(), member(), "3")
                6 -> r("RPUSH", "l" + key(), value(), value())
                7 -> r("LPOP", "l" + key(), "3")
                8 -> r("LTRIM", "l" + key(), "1", "-2")
                9 -> r("LREM", "l" + key(), "0", value())
                10 -> r("SADD", "s" + key(), member(), member(), member())
                11 -> r("SREM", "s" + key(), member())
                12 -> r("SPOP", "s" + key())
                13 -> r("ZADD", "z" + key(), random.nextInt(50).toString(), member())
                14 -> r("ZREM", "z" + key(), member())
                15 -> r("ZREMRANGEBYRANK", "z" + key(), "0", "2")
                16 -> r("ZINCRBY", "z" + key(), "1.5", member())
                17 -> r("SET", key(), value(), "PX", "5")
                18 -> db.now += random.nextLong(0, 10)
                19 -> r("RENAME", key(), key())
                20 -> r("LSET", "l" + key(), "0", value())
                21 -> r("GET", key())
                22 -> r("EXPIRE", key(), "3")
                23 -> r("PERSIST", key())
                24 -> r("SET", key(), value(), "KEEPTTL")
                25 -> r("GETEX", key(), "PX", "4")
            }
            if (step % 250 == 0) check(step)
        }
        check(20_000)
        assertTrue(db.usedMemory > MemoryModel.buckets(db.keyspace.capacity), "the workload left data behind")
    }

    @Test
    fun `DEL and UNLINK lower used_memory by the same amount before they reply`() {
        val mb = "x".repeat(1 shl 20)
        r("SET", "a", mb)
        r("SET", "b", mb)
        val full = db.usedMemory
        r("DEL", "a")
        val afterDel = db.usedMemory
        r("UNLINK", "b")
        val afterUnlink = db.usedMemory
        assertEquals(full - afterDel, afterDel - afterUnlink)
        assertTrue(full - afterDel > 1 shl 20, "a megabyte and its entry, not ${full - afterDel}")
    }

    @Test
    fun `deleting everything leaves only the buckets`() {
        repeat(500) { r("SET", "s$it", "x".repeat(it)) }
        repeat(20) { r("ZADD", "z", it.toString(), "m$it") }
        repeat(300) { r("HSET", "h", "f$it", "v") }
        db.keyspace.forEach { } // no mutation, just a walk
        val keys = ArrayList<String>()
        db.keyspace.forEach { keys.add(it.key.decodeToString()) }
        keys.chunked(50).forEach { chunk -> r("DEL", *chunk.toTypedArray()) }
        assertEquals(
            MemoryModel.buckets(db.keyspace.capacity) + MemoryModel.buckets(db.expires.capacity),
            db.usedMemory,
        )
        assertEquals(db.recount(), db.usedMemory)
    }

    @Test
    fun `an expired key leaves the total when anything finds it`() {
        r("SET", "gone", "x".repeat(1000), "PX", "10")
        val with = db.usedMemory
        db.now += 11
        r("GET", "gone")
        assertTrue(db.usedMemory < with - 1000, "${db.usedMemory} of $with")
        assertEquals(db.recount(), db.usedMemory)
    }
}
