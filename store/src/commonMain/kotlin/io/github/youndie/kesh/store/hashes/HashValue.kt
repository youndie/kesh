package io.github.youndie.kesh.store.hashes

import io.github.youndie.kesh.store.keyspace.Keyspace

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
    private var packed: ByteArray = EMPTY
    private var packedCount = 0
    private var table: Keyspace? = null

    val size: Int get() = table?.size ?: packedCount

    /** Whether the hash is still in its packed encoding. Invisible to clients; for tests. */
    val isPacked: Boolean get() = table == null

    fun get(field: ByteArray): ByteArray? {
        table?.let { return it.get(field)?.value as ByteArray? }
        val at = find(field)
        return if (at < 0) null else readBytes(packed, skip(packed, at))
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
            val valueAt = skip(packed, at)
            val valueEnd = skip(packed, valueAt)
            packed = packed.copyOfRange(0, valueAt) + encode(value) + packed.copyOfRange(valueEnd, packed.size)
            return false
        }
        packed = packed + encode(field) + encode(value)
        packedCount++
        if (packedCount > maxPackedEntries) convert()
        return true
    }

    /** Removes [field]; `true` if it was there. A hash never goes back to packed, as in Redis 7.2. */
    fun delete(field: ByteArray): Boolean {
        table?.let { return it.remove(field) != null }
        val at = find(field)
        if (at < 0) return false
        val end = skip(packed, skip(packed, at))
        packed = packed.copyOfRange(0, at) + packed.copyOfRange(end, packed.size)
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
            val valueAt = skip(packed, at)
            action(readBytes(packed, at), readBytes(packed, valueAt))
            at = skip(packed, valueAt)
        }
    }

    private fun convert() {
        val converted = Keyspace(seed)
        forEach { field, value -> converted.put(field, value) }
        table = converted
        packed = EMPTY
        packedCount = 0
    }

    /** The offset of [field]'s length prefix, or -1. Fields sit at even positions only. */
    private fun find(field: ByteArray): Int {
        var at = 0
        while (at < packed.size) {
            if (matches(packed, at, field)) return at
            at = skip(packed, skip(packed, at))
        }
        return -1
    }

    companion object {
        /** `hash-max-listpack-entries`, Redis 7.2's default. */
        var maxPackedEntries: Int = 512

        /** `hash-max-listpack-value`, Redis 7.2's default. */
        var maxPackedValue: Int = 64

        private val EMPTY = ByteArray(0)

        /** A length as an unsigned LEB128 varint, then the bytes. */
        private fun encode(bytes: ByteArray): ByteArray {
            var length = bytes.size
            val prefix = ArrayList<Byte>(2)
            do {
                var b = length and 0x7f
                length = length ushr 7
                if (length != 0) b = b or 0x80
                prefix.add(b.toByte())
            } while (length != 0)
            return prefix.toByteArray() + bytes
        }

        /** The length at [at] and the offset its bytes start at, packed into one `Long`. */
        private fun header(
            data: ByteArray,
            at: Int,
        ): Long {
            var length = 0
            var shift = 0
            var i = at
            while (true) {
                val b = data[i++].toInt() and 0xff
                length = length or ((b and 0x7f) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return (length.toLong() shl 32) or i.toLong()
        }

        private fun skip(
            data: ByteArray,
            at: Int,
        ): Int = header(data, at).let { (it and 0xffffffffL).toInt() + (it ushr 32).toInt() }

        private fun readBytes(
            data: ByteArray,
            at: Int,
        ): ByteArray =
            header(data, at).let {
                val start = (it and 0xffffffffL).toInt()
                data.copyOfRange(start, start + (it ushr 32).toInt())
            }

        private fun matches(
            data: ByteArray,
            at: Int,
            field: ByteArray,
        ): Boolean {
            val h = header(data, at)
            if ((h ushr 32).toInt() != field.size) return false
            val start = (h and 0xffffffffL).toInt()
            for (i in field.indices) if (data[start + i] != field[i]) return false
            return true
        }
    }
}
