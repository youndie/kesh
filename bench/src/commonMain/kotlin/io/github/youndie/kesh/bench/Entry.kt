package io.github.youndie.kesh.bench

/** One key of the reference dataset, with its value in the shape Redis would hold it. */
sealed interface Entry {
    val part: Part
    val key: ByteArray

    /**
     * What appendix A calls "user data" for this key: the key, the values, and 8 bytes per sorted-set
     * score. Nothing a store adds (headers, indexes, expiry records) — that difference is what B-11
     * measures.
     */
    val userBytes: Long
}

class StringEntry(
    override val part: Part,
    override val key: ByteArray,
    val value: ByteArray,
    /** Relative, in seconds, so the script is the same whenever it is generated or loaded. */
    val ttlSeconds: Int?,
) : Entry {
    override val userBytes: Long get() = (key.size + value.size).toLong()
}

class HashEntry(
    override val part: Part,
    override val key: ByteArray,
    val fields: List<Pair<ByteArray, ByteArray>>,
) : Entry {
    override val userBytes: Long get() = key.size + fields.sumOf { (f, v) -> (f.size + v.size).toLong() }
}

class ListEntry(
    override val part: Part,
    override val key: ByteArray,
    val items: List<ByteArray>,
) : Entry {
    override val userBytes: Long get() = key.size + items.sumOf { it.size.toLong() }
}

class SetEntry(
    override val part: Part,
    override val key: ByteArray,
    val members: List<ByteArray>,
) : Entry {
    override val userBytes: Long get() = key.size + members.sumOf { it.size.toLong() }
}

class SortedSetEntry(
    override val part: Part,
    override val key: ByteArray,
    val members: List<ByteArray>,
    /** Whole numbers, written as such; each counts as a double's 8 bytes of user data. */
    val scores: LongArray,
) : Entry {
    override val userBytes: Long get() = key.size + members.sumOf { it.size.toLong() + SCORE_BYTES }

    private companion object {
        const val SCORE_BYTES = 8L
    }
}
