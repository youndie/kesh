package io.github.youndie.kesh.store

import io.github.youndie.kesh.resp.encode
import io.github.youndie.kesh.store.commands.StoreCommands
import io.github.youndie.kesh.store.expiry.ActiveExpiry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** The active expiry scenario of `feature-keyspace`, with the clock moved by hand between cycles. */
class ActiveExpiryTest {
    private val db = Db(seed = 3).apply { now = 1_000_000 }
    private val commands = StoreCommands.all.associateBy { it.name }
    private val started = TimeSource.Monotonic.markNow()
    private val expiry = ActiveExpiry { started.elapsedNow().inWholeMicroseconds }

    private fun r(vararg args: String) =
        commands.getValue(args[0].lowercase()).run(db, args.map { it.encodeToByteArray() })

    @Test
    fun `keys never read again are gone within two seconds`() {
        repeat(100_000) { r("SET", "k$it", "v", "PX", "50") }
        repeat(1_000) { r("SET", "stay$it", "v") }
        assertEquals(100_000, db.expires.size)
        // Twenty cycles, a hundred milliseconds apart: two seconds, as the server runs them.
        repeat(20) {
            db.now += 100
            expiry.cycle(db)
            db.resizeAndRehash()
        }
        val left = db.size - 1_000
        assertTrue(left <= 1_000, "$left of 100 000 still there after 2 s")
        assertEquals(100_000L - left, db.expiredKeys)
        assertEquals(left, db.expires.size)
        assertEquals(db.recount(), db.usedMemory)
    }

    @Test
    fun `avg_ttl follows the time the sampled keys have left and is 0 with none`() {
        assertEquals(0L, expiry.avgTtl)
        repeat(200) { r("SET", "k$it", "v", "PX", "100000") }
        expiry.cycle(db)
        assertEquals(100_000L, expiry.avgTtl, "the first loop's average is taken whole")
        db.now += 50_000
        repeat(30) { expiry.cycle(db) }
        assertTrue(expiry.avgTtl in 50_001 until 100_000, "moves toward 50 000 by 2 % a loop: ${expiry.avgTtl}")
        r("FLUSHALL")
        expiry.cycle(db)
        assertEquals(0L, expiry.avgTtl)
    }

    @Test
    fun `a key with time left is not touched and a persisted one leaves the index`() {
        r("SET", "later", "v", "EX", "100")
        r("SET", "soon", "v", "PX", "10")
        r("SET", "kept", "v", "PX", "10")
        r("PERSIST", "kept")
        db.now += 11
        repeat(3) { expiry.cycle(db) }
        assertEquals(":1\r\n", r("EXISTS", "later").encode().decodeToString())
        assertEquals(1, db.expires.size)
        assertEquals(1L, db.expiredKeys)
        assertEquals(2, db.size)
    }
}
