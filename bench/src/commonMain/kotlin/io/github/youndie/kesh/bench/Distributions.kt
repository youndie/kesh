package io.github.youndie.kesh.bench

import kotlin.math.ln
import kotlin.math.pow

/**
 * A length in `[min, max]` whose mean is [mean]: `min + (max - min) · u^k`, with `k` chosen so the
 * continuous mean is right. Appendix A gives a range *and* a total for each part; a uniform draw over
 * the range misses most totals by 5–10 %, and this keeps both.
 */
class SkewedRange(
    private val min: Int,
    private val max: Int,
    mean: Double,
) {
    init {
        require(mean > min && mean < max) { "mean $mean is outside ($min, $max)" }
    }

    private val exponent = (max - min) / (mean - min) - 1.0

    fun next(rng: Rng): Int = (min + (max - min + 1) * rng.nextDouble().pow(exponent)).toInt().coerceAtMost(max)
}

/**
 * A truncated power law on `[min, max]` with density ∝ x^-a, `a` solved so the mean is [mean].
 *
 * This is how leaderboard sizes are drawn, and it is not a stylistic choice: appendix A asks for
 * 2 000 boards of 1 000–100 000 members holding 0.3 GB, and a uniform draw over that range holds
 * 1.6 GB. The table is only consistent if most boards are small and a few are large — which is what
 * leaderboards look like — with a mean near 9 700 members.
 */
class PowerLaw(
    private val min: Double,
    private val max: Double,
    mean: Double,
) {
    private val a: Double = solve(mean)

    fun next(rng: Rng): Int = at(rng.nextDouble())

    /**
     * [count] draws whose sample mean is the distribution's mean, not a sample of it: one draw from
     * each of [count] equal slices of probability, in shuffled order. With 2 000 leaderboards a plain
     * sample of this heavy tail misses the mean by several per cent (seed 42: +5.0 %), which says
     * nothing about the store and everything about the seed.
     */
    fun stratified(
        count: Int,
        rng: Rng,
    ): IntArray {
        val draws = IntArray(count) { at((it + rng.nextDouble()) / count) }
        for (i in count - 1 downTo 1) {
            val j = rng.nextInt(0, i + 1)
            val t = draws[i]
            draws[i] = draws[j]
            draws[j] = t
        }
        return draws
    }

    private fun at(u: Double): Int {
        val lo = min.pow(1 - a)
        val hi = max.pow(1 - a)
        return (lo + u * (hi - lo)).pow(1 / (1 - a)).toInt().coerceIn(min.toInt(), max.toInt())
    }

    private fun meanOf(a: Double): Double {
        if (kotlin.math.abs(a - 2.0) < 1e-9) return (ln(max) - ln(min)) / (1 / min - 1 / max)
        val num = (max.pow(2 - a) - min.pow(2 - a)) / (2 - a)
        val den =
            if (kotlin.math.abs(a - 1.0) <
                1e-9
            ) {
                ln(max) - ln(min)
            } else {
                (max.pow(1 - a) - min.pow(1 - a)) / (1 - a)
            }
        return num / den
    }

    private fun solve(target: Double): Double {
        require(target > min && target < max) { "mean $target is outside ($min, $max)" }
        // The mean falls monotonically as `a` grows; bisect between nearly uniform and very steep.
        var lo = 0.001
        var hi = 10.0
        repeat(200) {
            val mid = (lo + hi) / 2
            if (meanOf(mid + 1e-12) > target) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }
}
