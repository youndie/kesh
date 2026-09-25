package io.github.youndie.kesh.store.hashes

import io.github.youndie.kesh.store.keyspace.Keyspace
import io.github.youndie.kesh.store.packed.Packed

/**
 * A hash: field → value, both bytes. Two encodings, as Redis has (`redis/redis@7.2!/src/t_hash.c`):
 *
 * - **packed** — one `ByteArray` of length-prefixed field and value pairs in insertion order, Redis's
 *   listpack in role. One object for the collector instead of two per field (research D-3, B-19:
 *   for memory, not for the pause). Lookups scan it, as Redis's do.
 * - **table** — kesh's own [Keyspace], the table the keyspace itself uses, so `HSCAN` (B-10) gets the
 *   same cursor guarantee as `SCAN`.
 *
 * A hash starts packed and becomes a table, once and for good, under Redis 7.2's rules with Redis's
 * defaults: more than [maxPackedEntries] fields, or a field or value longer than [maxPackedValue]
 * bytes (`hashTypeTryConversion`, `hashTypeSet`; `hash-max-listpack-entries 512`,
 * `hash-max-listpack-value 64`). Following Redis exactly is what makes the encoding invisible: a
 * packed hash replies in insertion order in both, and a table in table order in both (research D-20).
 */
class HashValue(
    private val seed: Int,
) {
    private var packed: ByteArray = Packed.EMPTY
    private var packedCount = 0

    /** The table, once the hash is no longer packed; `HSCAN` walks it. */
    internal var table: Keyspace? = null
        private set

    val size: Int get() = table?.size ?: packedCount

    /** Whether the hash is still in its packed encoding. Invisible to clients; for tests. */
    val isPacked: Boolean get() = table == null

    fun get(field: ByteArray): ByteArray? {
        table?.let { return it.get(field)?.value as ByteArray? }
        val at = find(field)
        return if (at < 0) null else Packed.read(packed, Packed.skip(packed, at))
    }

    fun contains(field: ByteArray): Boolean = table?.get(field) != null || (table == null && find(field) >= 0)

    /**
     * Sets [field] to [value]; `true` if the field is new. `hashTypeSet`: a field or value too long to
     * pack converts the hash before the write, too many fields after it.
     */
    fun set(
        field: ByteArray,
        value: ByteArray,
    ): Boolean {
        if (table == null && (field.size > maxPackedValue || value.size > maxPackedValue)) convert()
        table?.let { t ->
            val created = t.get(field) == null
            t.put(field, value)
            return created
        }
        val at = find(field)
        if (at >= 0) {
            val valueAt = Packed.skip(packed, at)
            packed = Packed.splice(packed, valueAt, Packed.skip(packed, valueAt), Packed.encode(value))
            return false
        }
        packed = packed + Packed.encode(field) + Packed.encode(value)
        packedCount++
        if (packedCount > maxPackedEntries) convert()
        return true
    }

    /** Removes [field]; `true` if it was there. A hash never goes back to packed, as in Redis 7.2. */
    fun delete(field: ByteArray): Boolean {
        table?.let { return it.remove(field) != null }
        val at = find(field)
        if (at < 0) return false
        packed = Packed.splice(packed, at, Packed.skip(packed, at, 2))
        packedCount--
        return true
    }

    /**
     * `hashTypeTryConversion`, before a write of the field–value pairs in [arguments] from [from] on:
     * converts if they alone are more pairs than a packed hash holds, or if any is too long to pack.
     */
    fun prepareFor(
        arguments: List<ByteArray>,
        from: Int,
    ) {
        if (table != null) return
        if ((arguments.size - from) / 2 > maxPackedEntries) return convert()
        for (i in from until arguments.size) if (arguments[i].size > maxPackedValue) return convert()
    }

    /** Every field and value: insertion order while packed, table order after. */
    fun forEach(action: (field: ByteArray, value: ByteArray) -> Unit) {
        table?.let { t ->
            t.forEach { action(it.key, it.value as ByteArray) }
            return
        }
        var at = 0
        while (at < packed.size) {
            val valueAt = Packed.skip(packed, at)
            action(Packed.read(packed, at), Packed.read(packed, valueAt))
            at = Packed.skip(packed, valueAt)
        }
    }

    private fun convert() {
        val converted = Keyspace(seed)
        forEach { field, value -> converted.put(field, value) }
        table = converted
        packed = Packed.EMPTY
        packedCount = 0
    }

    /** The offset of [field]'s length prefix, or -1. Fields sit at even positions only. */
    private fun find(field: ByteArray): Int {
        var at = 0
        while (at < packed.size) {
            if (Packed.matches(packed, at, field)) return at
            at = Packed.skip(packed, at, 2)
        }
        return -1
    }

    companion object {
        /** `hash-max-listpack-entries`, Redis 7.2's default. */
        var maxPackedEntries: Int = 512

        /** `hash-max-listpack-value`, Redis 7.2's default. */
        var maxPackedValue: Int = 64
    }
}
