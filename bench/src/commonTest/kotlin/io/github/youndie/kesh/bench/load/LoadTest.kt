package io.github.youndie.kesh.bench.load

import io.github.youndie.kesh.bench.Rng
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pieces of the reference load that decide what it measures (B-17). */
class LoadTest {
    @Test
    fun `zipf draws rank r in proportion to 1 over r to the exponent`() {
        val zipf = Zipf(1_000, exponent = 0.99, seed = 7)
        val rng = Rng(1)
        val counts = IntArray(1_000)
        repeat(1_000_000) { counts[zipf.rank(rng)]++ }
        val norm = (1..1_000).sumOf { 1.0 / it.toDouble().pow(0.99) }
        for (r in listOf(0, 1, 9, 99)) {
            val expected = 1_000_000 / (r + 1.0).pow(0.99) / norm
            assertTrue(abs(counts[r] - expected) < 0.05 * expected + 50, "rank $r: ${counts[r]} against $expected")
        }
        val keys = HashSet<Int>()
        repeat(10_000) { keys += zipf.next(rng) }
        assertTrue(0 !in keys || keys.size > 300, "the permutation spreads the hot keys")
    }

    @Test
    fun `the mix is 80 percent reads and 20 percent writes`() {
        val keys = KeyCatalogue.of(42, 0.0005)
        val workload = Workload(keys, 42)
        val rng = Rng(3)
        var writes = 0
        repeat(100_000) { if (workload.operation(rng).write) writes++ }
        assertTrue(writes in 19_500..20_500, "$writes writes in 100 000")
        assertEquals(100, Workload.Operation.entries.sumOf { it.weight })
        assertEquals(
            20,
            Workload.Operation.entries
                .filter { it.write }
                .sumOf { it.weight },
        )
    }

    @Test
    fun `the catalogue holds the dataset's own keys`() {
        val keys = KeyCatalogue.of(42, 0.0005)
        assertEquals(5_000, keys.sessions.size)
        assertTrue(keys.sessions.all { it.decodeToString().startsWith("session:") })
        assertEquals(keys.profiles.size, keys.profileFields.size)
        assertTrue(keys.boards.first().decodeToString() == "board:2026-09")
    }

    @Test
    fun `the reply counter counts whole replies fed in pieces and errors among them`() {
        val stream =
            "+OK\r\n:5\r\n$3\r\nabc\r\n$-1\r\n*2\r\n$1\r\na\r\n*1\r\n:1\r\n-ERR x\r\n*0\r\n"
                .encodeToByteArray()
        for (cut in 1 until stream.size) {
            val counter = ReplyCounter()
            val a = counter.feed(stream.copyOfRange(0, cut), cut)
            val rest = stream.copyOfRange(cut, stream.size)
            val b = counter.feed(rest, rest.size)
            assertEquals(7, a + b, "cut at $cut")
            assertEquals(1, counter.errors)
        }
    }

    @Test
    fun `histogram percentiles are within 3 percent`() {
        val h = LatencyHistogram()
        for (v in 1L..100_000L) h.record(v)
        for (f in listOf(0.5, 0.99, 0.999)) {
            val exact = (f * 100_000).toLong()
            val got = h.percentile(f)
            assertTrue(got >= exact && got <= exact * 1.033, "p$f: $got against $exact")
        }
        assertEquals(100_000, h.max)
        assertEquals(63, LatencyHistogram().apply { record(63) }.percentile(1.0))
    }
}
