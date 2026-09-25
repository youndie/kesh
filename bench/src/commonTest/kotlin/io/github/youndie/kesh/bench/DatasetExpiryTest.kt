package io.github.youndie.kesh.bench

import io.github.youndie.kesh.store.Db
import io.github.youndie.kesh.store.commands.StoreCommands
import io.github.youndie.kesh.store.expiry.ActiveExpiry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

/**
 * B-13's second criterion: the reference dataset's TTL keys — 30 % of sessions (1–24 h) and every
 * rate counter (2 min) — all go by active expiry once their time has passed, and `expired_keys`
 * counts exactly them. At 1/256 of the dataset; the clock is moved a day on, never waited for.
 */
class DatasetExpiryTest {
    @Test
    fun `every TTL key of the reference dataset goes and is counted`() {
        val db = Db(seed = 42).apply { now = 1_000_000 }
        val commands = StoreCommands.all.associateBy { it.name }

        fun run(args: List<ByteArray>) = commands.getValue(args[0].decodeToString().lowercase()).run(db, args)
        var withTtl = 0L
        var keys = 0
        ReferenceDataset(seed = 42, scale = 1.0 / 256).entries().forEach { entry ->
            keys++
            when (entry) {
                is StringEntry -> {
                    val ttl = entry.ttlSeconds
                    if (ttl != null) withTtl++
                    run(
                        listOf("SET".bytes(), entry.key, entry.value) +
                            (ttl?.let { listOf("EX".bytes(), "$it".bytes()) } ?: emptyList()),
                    )
                }

                is HashEntry -> {
                    run(listOf("HSET".bytes(), entry.key) + entry.fields.flatMap { listOf(it.first, it.second) })
                }

                is ListEntry -> {
                    run(listOf("RPUSH".bytes(), entry.key) + entry.items)
                }

                is SetEntry -> {
                    run(listOf("SADD".bytes(), entry.key) + entry.members)
                }

                is SortedSetEntry -> {
                    run(
                        listOf("ZADD".bytes(), entry.key) +
                            entry.members.indices.flatMap { listOf("${entry.scores[it]}".bytes(), entry.members[it]) },
                    )
                }
            }
        }
        assertEquals(keys, db.size)
        assertEquals(withTtl, db.expires.size.toLong())
        val started = TimeSource.Monotonic.markNow()
        val expiry = ActiveExpiry { started.elapsedNow().inWholeMicroseconds }
        db.now += 25L * 3_600_000
        var cycles = 0
        while (db.expires.size > 0 && cycles < 10_000) {
            expiry.cycle(db)
            db.resizeAndRehash()
            cycles++
        }
        assertEquals(0, db.expires.size, "TTL keys left after $cycles cycles")
        assertEquals(withTtl, db.expiredKeys)
        assertEquals(keys - withTtl, db.size.toLong())
    }

    private fun String.bytes() = encodeToByteArray()
}
