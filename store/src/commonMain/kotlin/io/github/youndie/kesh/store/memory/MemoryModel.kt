package io.github.youndie.kesh.store.memory

import io.github.youndie.kesh.store.hashes.HashValue
import io.github.youndie.kesh.store.keyspace.Entry
import io.github.youndie.kesh.store.lists.ListValue
import io.github.youndie.kesh.store.sets.SetValue
import io.github.youndie.kesh.store.zsets.ZSetValue

/**
 * kesh's estimate of what its data costs on the Kotlin/Native heap — `used_memory` (research D-10).
 * An estimate, not a measurement: object and array sizes as the 64-bit runtime lays them out, header
 * and alignment included, and nothing the collector adds (unswept garbage, free space in pages, the
 * allocator's per-thread pages). How far resident memory stands above this sum is B-11's
 * measurement, and the reason `maxmemory` is checked against the container with a ratio.
 */
object MemoryModel {
    /** An object's header: its type pointer and the collector's word. */
    const val OBJECT = 16L

    /** A reference or a `Long`/`Double` field. */
    const val WORD = 8L

    /** An [Entry]: header, key, value, next, expiry, hash (padded). */
    const val ENTRY = OBJECT + 4 * WORD + 8

    /** An array of [payload] bytes: header and length word, then the payload, to 8 bytes. */
    fun array(payload: Long): Long = align(OBJECT + WORD + payload)

    fun align(bytes: Long): Long = (bytes + 7) and 7L.inv()

    /** One key's cost: its entry, its key's bytes, its value. */
    fun entry(entry: Entry): Long = ENTRY + array(entry.key.size.toLong()) + value(entry.value)

    fun value(value: Any): Long =
        when (value) {
            is ByteArray -> array(value.size.toLong())
            is HashValue -> value.estimatedBytes
            is ListValue -> value.estimatedBytes
            is SetValue -> value.estimatedBytes
            is ZSetValue -> value.estimatedBytes
            else -> error("no estimate for ${value::class}")
        }

    /** A table's bucket arrays: a reference per bucket, in each table that exists. */
    fun buckets(capacity: Int): Long = array(WORD * capacity)
}
