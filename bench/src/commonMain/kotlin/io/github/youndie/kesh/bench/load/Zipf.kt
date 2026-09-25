package io.github.youndie.kesh.bench.load

import io.github.youndie.kesh.bench.Rng
import kotlin.math.pow

/**
 * A Zipf choice among [n] keys: rank r (1-based) with probability proportional to 1 / r^[exponent].
 * §5a says only "Keys follow a Zipf distribution"; 0.99 is YCSB's constant, the one load generators
 * share, and B-17's report names it. The ranks are shuffled over the keys by a seeded permutation, so
 * the hot keys are not simply the first ones the dataset wrote — nor the first in any table's order.
 *
 * Drawn by inverting the cumulative distribution with a binary search: 8 bytes a key, built once.
 */
class Zipf(
    val n: Int,
    val exponent: Double = 0.99,
    seed: Long = 1,
) {
    private val cumulative = DoubleArray(n)
    private val permutation = IntArray(n) { it }

    init {
        require(n > 0) { "no keys to choose from" }
        var sum = 0.0
        for (r in 0 until n) {
            sum += 1.0 / (r + 1.0).pow(exponent)
            cumulative[r] = sum
        }
        for (r in 0 until n) cumulative[r] /= sum
        val rng = Rng(seed)
        for (i in n - 1 downTo 1) {
            val j = rng.nextInt(0, i + 1)
            val t = permutation[i]
            permutation[i] = permutation[j]
            permutation[j] = t
        }
    }

    /** The rank drawn, 0-based: 0 is the most frequent. */
    fun rank(rng: Rng): Int {
        val u = rng.nextDouble()
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cumulative[mid] < u) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** The key index drawn: the rank, through the permutation. */
    fun next(rng: Rng): Int = permutation[rank(rng)]
}
