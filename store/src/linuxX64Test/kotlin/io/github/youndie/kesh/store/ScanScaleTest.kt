package io.github.youndie.kesh.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The feature's scenario at its size: a million keys, keys added and deleted while the scan runs,
// a resize in the middle of it. On linuxX64, the target that ships.
class ScanScaleTest {
    @Test
    fun `a million-key scan sees every stable key while keys come and go across a resize`() {
        val outcome = ScanCompleteness.run(keys = 1_000_000, added = 150_000, deleted = 50_000)
        assertTrue(outcome.resized, "the table must resize during the iteration")
        assertEquals(emptyList(), outcome.missed.take(5), "${outcome.missed.size} stable keys never seen")
    }
}
