package io.github.youndie.kesh.bench

/**
 * SplitMix64 — the generator behind every byte of the reference dataset.
 *
 * Not `kotlin.random.Random(seed)`: its contract promises the same sequence only "within the same
 * version of Kotlin runtime", and a dataset whose point is to be re-generated months later, for a
 * comparison against today's numbers, cannot depend on the compiler staying put. This is ten lines,
 * the same on every platform and every version, and pinned by `ReferenceDatasetTest`'s digest.
 */
class Rng(
    seed: Long,
) {
    private var state = seed

    fun nextLong(): Long {
        state += GOLDEN_GAMMA
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** Uniform in [0, 1). */
    fun nextDouble(): Double = (nextLong() ushr 11) * DOUBLE_UNIT

    /** Uniform in [from, until). */
    fun nextInt(
        from: Int,
        until: Int,
    ): Int = from + (nextDouble() * (until - from)).toInt()

    /** Uniform in [from, until). */
    fun nextLong(
        from: Long,
        until: Long,
    ): Long = from + (nextDouble() * (until - from)).toLong()

    /** A generator for a sub-stream, so that one part of the dataset never shifts another. */
    fun fork(salt: Long): Rng = Rng(nextLong() xor salt)

    private companion object {
        const val GOLDEN_GAMMA = -0x61c8864680b583ebL
        const val DOUBLE_UNIT = 1.0 / (1L shl 53)
    }
}
