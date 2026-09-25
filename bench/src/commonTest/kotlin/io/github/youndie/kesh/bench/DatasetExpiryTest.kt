package io.github.youndie.kesh.bench

import io.github.youndie.kesh.store.Db
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
        val (keys, withTtl) = DatasetLoader.load(ReferenceDataset(seed = 42, scale = 1.0 / 256), db)
        assertEquals(keys, db.size)
        assertEquals(withTtl, db.expires.size)
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
        assertEquals(withTtl.toLong(), db.expiredKeys)
        assertEquals(keys - withTtl, db.size)
    }
}
