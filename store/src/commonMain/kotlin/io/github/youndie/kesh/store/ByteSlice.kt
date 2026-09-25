package io.github.youndie.kesh.store

/**
 * A run of bytes where the store keeps it — inside a packed collection or a whole key or value — handed
 * over without a copy (B-25). Read it inside [accept]; the array is the store's and changes with it.
 *
 * An interface rather than a function type: a lambda's `Int` parameters would be boxed on every call,
 * and the point of the visit is that a background save's child, which has no collector, allocates
 * nothing per element (research R-6).
 */
fun interface ByteSlice {
    fun accept(
        data: ByteArray,
        offset: Int,
        length: Int,
    )
}

/** A sorted set's member where it lies, with its score. See [ByteSlice]. */
fun interface ScoredSlice {
    fun accept(
        data: ByteArray,
        offset: Int,
        length: Int,
        score: Double,
    )
}
