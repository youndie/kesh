package io.github.youndie.kesh.bench.load

/**
 * Latencies in microseconds, in log-linear buckets — exact below 64 µs, then 32 per power of two, so
 * a percentile is exact to within 3.2 %, over any range a run can see, in 15 KiB. Merged across
 * connections at the end, never shared while they run.
 */
class LatencyHistogram {
    private val counts = LongArray(BUCKETS)
    var count = 0L
        private set
    var max = 0L
        private set
    var sum = 0L
        private set

    fun record(micros: Long) {
        val v = maxOf(micros, 0)
        counts[bucket(v)]++
        count++
        sum += v
        if (v > max) max = v
    }

    fun add(other: LatencyHistogram) {
        for (i in counts.indices) counts[i] += other.counts[i]
        count += other.count
        sum += other.sum
        if (other.max > max) max = other.max
    }

    /** The smallest bucket's upper bound under which [fraction] of the recorded values fall. */
    fun percentile(fraction: Double): Long {
        if (count == 0L) return 0
        val wanted =
            kotlin.math
                .ceil(fraction * count)
                .toLong()
                .coerceAtLeast(1)
        var seen = 0L
        for (i in counts.indices) {
            seen += counts[i]
            if (seen >= wanted) return minOf(upper(i), max)
        }
        return max
    }

    /** Non-empty buckets as `upper-bound-µs count`, one a line: the raw record a report commits. */
    fun raw(): String =
        buildString {
            for (i in counts.indices) if (counts[i] > 0) append(upper(i)).append(' ').append(counts[i]).append('\n')
        }

    private companion object {
        /** Exact below 64 µs; then 32 linear buckets in each power of two. */
        const val LINEAR = 64
        const val PER_POWER = 32
        const val BUCKETS = LINEAR + (63 - 6) * PER_POWER

        fun bucket(v: Long): Int {
            if (v < LINEAR) return v.toInt()
            val k = 63 - v.countLeadingZeroBits() // v in [2^k, 2^(k+1)), k >= 6
            return LINEAR + (k - 6) * PER_POWER + ((v ushr (k - 5)).toInt() - PER_POWER)
        }

        fun upper(i: Int): Long {
            if (i < LINEAR) return i.toLong()
            val j = i - LINEAR
            val k = j / PER_POWER + 6
            return ((PER_POWER + j % PER_POWER + 1).toLong() shl (k - 5)) - 1
        }
    }
}
