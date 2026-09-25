package io.github.youndie.kesh.server.http

import io.github.youndie.kesh.server.BuildInfo
import io.github.youndie.kesh.server.info.CommandStats
import io.github.youndie.kesh.server.info.GcStats
import kotlin.test.Test
import kotlin.test.assertEquals

/** The exposition's histogram at its edges, which a test through the server cannot reach in time. */
class MetricsTest {
    @Test
    fun `a command slower than the last bound lands in +Inf and the buckets still add up`() {
        val stats = CommandStats()
        stats.record("save", 30)
        stats.record("save", 2_000_000)
        stats.record("save", 1_000_000)
        val store = Metrics.Store(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, stats.snapshot(), GcStats { null }.snapshot())
        val samples = PrometheusText.parse(Metrics.render(store)).getValue("kesh_command_duration_seconds")

        fun bucket(le: String) = samples.single { it.name.endsWith("_bucket") && it.labels["le"] == le }.value
        assertEquals(1.0, bucket("0.00005"))
        assertEquals(2.0, bucket("1"), "a bound is inclusive: 1 s is at or under 1")
        assertEquals(3.0, bucket("+Inf"))
        assertEquals(3.0, samples.single { it.name.endsWith("_count") }.value)
        assertEquals(3.00003, samples.single { it.name.endsWith("_sum") }.value)
    }

    @Test
    fun `the build info names the allocator page size the binary was built with`() {
        // Before the server starts too: a scrape during the snapshot load already says which arm it is.
        val sample = PrometheusText.parse(Metrics.render(null)).getValue("kesh_build_info").single()
        assertEquals(mapOf("allocator_page_size_kb" to "${BuildInfo.ALLOCATOR_PAGE_SIZE_KB}"), sample.labels)
        assertEquals(1.0, sample.value)
    }

    @Test
    fun `the collector's families parse and carry what was polled`() {
        val polled = ArrayDeque(listOf(GcStats.Collection(2, 900, 3_000, 40_000, 12_345)))
        val gc = GcStats { polled.removeFirstOrNull() }.apply { poll() }
        val store = Metrics.Store(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, emptyList(), gc.snapshot())
        val families = PrometheusText.parse(Metrics.render(store))
        val pauses = families.getValue("kesh_gc_pause_seconds")

        fun sum(pause: String) = pauses.single { it.name.endsWith("_sum") && it.labels["pause"] == pause }.value
        assertEquals(0.0009, sum("first"))
        assertEquals(0.003, sum("second"))
        assertEquals(0.04, families.getValue("kesh_gc_duration_seconds").single { it.name.endsWith("_sum") }.value)
        assertEquals(1.0, families.getValue("kesh_gc_collections_total").single().value)
        assertEquals(
            1.0,
            families.getValue("kesh_gc_epochs_missed_total").single().value,
            "epoch 1 came before the first poll",
        )
        assertEquals(12_345.0, families.getValue("kesh_gc_marked_objects").single().value)
    }
}
