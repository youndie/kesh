package io.github.youndie.kesh.server.info

import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The collector's collections as `/metrics` exports them (B-31). */
class GcStatsTest {
    private fun collection(
        epoch: Long,
        second: Long? = 200,
    ) = GcStats.Collection(epoch, 100, second, 5_000, 1_000 * epoch)

    @Test
    fun `each collection is exported once and a gap between polls is counted as missed`() {
        val script = ArrayDeque(listOf(null, collection(3), collection(3), collection(4, second = null), collection(7)))
        val stats = GcStats { script.removeFirst() }
        assertNull(stats.poll(), "no collection yet")
        assertEquals(3L, stats.poll()?.epoch)
        assertNull(stats.poll(), "the same epoch again is not a new collection")
        assertEquals(4L, stats.poll()?.epoch)
        assertEquals(7L, stats.poll()?.epoch)
        val s = stats.snapshot()
        assertEquals(3, s.collections)
        assertEquals(4, s.missed, "epochs 1 and 2 before the first poll, 5 and 6 between two")
        assertEquals(3, s.firstPause.count)
        assertEquals(2, s.secondPause.count, "a collection with no second pause adds none")
        assertEquals(7_000, s.markedObjects)
    }

    @OptIn(NativeRuntimeApi::class)
    @Test
    fun `the runtime reports finished collections in a build with no flags`() {
        GC.collect()
        GC.collect()
        val c = assertNotNull(GcStats.lastFinished(), "lastGCInfo after two collections")
        assertTrue(c.epoch >= 1, "epoch ${c.epoch}")
        assertTrue(
            c.firstPauseMicros >= 0 && c.durationMicros >= c.firstPauseMicros,
            "pause ${c.firstPauseMicros} µs of ${c.durationMicros} µs",
        )
        assertTrue(c.markedObjects > 0, "marked ${c.markedObjects}")
    }
}
