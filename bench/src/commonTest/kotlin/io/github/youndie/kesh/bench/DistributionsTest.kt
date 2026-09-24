package io.github.youndie.kesh.bench

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DistributionsTest {
    private fun mean(
        draws: Int,
        next: (Rng) -> Int,
    ): Double {
        val rng = Rng(1)
        var sum = 0.0
        repeat(draws) { sum += next(rng) }
        return sum / draws
    }

    @Test
    fun `a skewed range hits its mean and stays in range`() {
        val range = SkewedRange(150, 400, mean = 236.0)
        val rng = Rng(3)
        repeat(100_000) { assertTrue(range.next(rng) in 150..400) }
        val m = mean(200_000) { range.next(it) }
        assertTrue(abs(m / 236.0 - 1) < 0.01, "mean $m")
    }

    @Test
    fun `a power law hits its mean and stays in range`() {
        val law = PowerLaw(1_000.0, 100_000.0, mean = 9_730.0)
        val rng = Rng(3)
        repeat(100_000) { assertTrue(law.next(rng) in 1_000..100_000) }
        val m = mean(400_000) { law.next(it) }
        assertTrue(abs(m / 9_730.0 - 1) < 0.02, "mean $m")
    }

    @Test
    fun `a stratified power law has the mean the table needs at any seed`() {
        listOf(1L, 42L, 1000L).forEach { seed ->
            val draws = PowerLaw(1_000.0, 100_000.0, mean = 9_730.0).stratified(2_000, Rng(seed))
            assertTrue(draws.all { it in 1_000..100_000 })
            assertTrue(abs(draws.average() / 9_730.0 - 1) < 0.005, "seed $seed mean ${draws.average()}")
        }
    }

    @Test
    fun `the generator is SplitMix64 value for value`() {
        // The published first output of SplitMix64 seeded with 0 (Vigna's reference implementation).
        assertEquals(0xe220a8397b1dcdafUL, Rng(0).nextLong().toULong())
    }
}
