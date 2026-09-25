package io.github.youndie.kesh.snapshot

import io.github.youndie.kesh.store.ByteSlice
import io.github.youndie.kesh.store.Db
import io.github.youndie.kesh.store.ScoredSlice
import io.github.youndie.kesh.store.hashes.HashValue
import io.github.youndie.kesh.store.keyspace.Entry
import io.github.youndie.kesh.store.lists.ListValue
import io.github.youndie.kesh.store.sets.SetValue
import io.github.youndie.kesh.store.zsets.ZSetValue

/** Where a snapshot's bytes go. */
fun interface ByteSink {
    fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    )
}

/** Where a snapshot's bytes come from: fills up to [length] bytes, returns how many, or -1 at the end. */
fun interface ByteSource {
    fun read(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int
}

/** A snapshot that cannot be read: short, damaged, or not one. Loading stops, and nothing it read is kept. */
class SnapshotException(
    message: String,
) : Exception(message)

/**
 * kesh's snapshot format, version 1 (brief §2: its own, not RDB). One file, in this order:
 *
 * - the header: `KESHSNAP`, the version (u32), the time it was taken (i64, ms since the epoch);
 * - one record per key: its type (u8), the key, its absolute expiry in ms or -1, the value — a string
 *   is its bytes; a hash, a list, a set or a sorted set is a count, then its fields and values,
 *   items, members, or members and scores (the score as its IEEE bits);
 * - the end: a 0xFF type, the number of records (i64), and the CRC-32 of every byte before the CRC
 *   itself (u32).
 *
 * Lengths and counts are unsigned LEB128, fixed-width numbers big-endian. The CRC and the count at the
 * end are what tells a whole file from a torn one; the server writes to a temporary name and renames,
 * so a torn file never takes the real one's place.
 */
object Snapshot {
    private val MAGIC = "KESHSNAP".encodeToByteArray()
    const val VERSION = 1

    private const val STRING = 1
    private const val HASH = 2
    private const val LIST = 3
    private const val SET = 4
    private const val ZSET = 5
    private const val END = 0xff

    /** What a save wrote. */
    class Written(
        val keys: Long,
        val bytes: Long,
    )

    /**
     * Writes [db]'s live keys as of [Db.now] — an expired key is left out, as Redis leaves it out — to
     * [sink]. The caller holds the store thread for the whole call: the snapshot is the dataset as of
     * the moment it started (`SAVE`).
     */
    fun write(
        db: Db,
        sink: ByteSink,
    ): Written {
        val out = Out(sink)
        out.bytes(MAGIC)
        out.u32(VERSION)
        out.i64(db.now)
        var keys = 0L
        db.keyspace.forEach { entry ->
            if (db.isExpired(entry)) return@forEach
            val value = entry.value
            out.u8(typeOf(value))
            out.blob(entry.key)
            out.i64(entry.expireAt)
            when (value) {
                is ByteArray -> {
                    out.blob(value)
                }

                // The elements are copied from where the store keeps them, never out into arrays of
                // their own: a background save's child has no collector, and would keep every copy
                // until it exits (B-25, research R-6).
                is HashValue -> {
                    out.count(value.size)
                    value.forEachSlice(out)
                }

                is ListValue -> {
                    out.count(value.size)
                    value.forEachSlice(out)
                }

                is SetValue -> {
                    out.count(value.size)
                    value.forEachSlice(out)
                }

                is ZSetValue -> {
                    out.count(value.size)
                    value.forEachSlice(out)
                }
            }
            keys++
        }
        out.u8(END)
        out.i64(keys)
        out.flush()
        out.u32(out.crc.value)
        out.flush()
        return Written(keys, out.written)
    }

    /**
     * Reads a snapshot from [source] into [db], which should be empty, at [Db.now]: a key whose expiry
     * has passed is not loaded. Throws [SnapshotException] if the file is not a whole snapshot; the
     * caller then discards [db]. Returns the number of keys loaded.
     */
    fun read(
        db: Db,
        source: ByteSource,
    ): Long {
        val input = In(source)
        if (!input.bytes(MAGIC.size).contentEquals(MAGIC)) throw SnapshotException("not a kesh snapshot")
        val version = input.u32()
        if (version != VERSION) throw SnapshotException("snapshot version $version; this kesh reads $VERSION")
        input.i64() // when it was taken
        var records = 0L
        var loaded = 0L
        while (true) {
            val type = input.u8()
            if (type == END) break
            val key = input.blob()
            val expireAt = input.i64()
            val value: Any =
                when (type) {
                    STRING -> {
                        input.blob()
                    }

                    HASH -> {
                        val count = input.count()
                        HashValue(db.seed).apply { repeat(count) { set(input.blob(), input.blob()) } }
                    }

                    LIST -> {
                        val count = input.count()
                        ListValue().apply { repeat(count) { pushLast(input.blob()) } }
                    }

                    SET -> {
                        val count = input.count()
                        SetValue(db.seed).apply {
                            prepareFor(count)
                            repeat(count) { add(input.blob()) }
                        }
                    }

                    ZSET -> {
                        val count = input.count()
                        ZSetValue(db.seed).apply {
                            prepareFor(count)
                            repeat(count) { put(input.blob(), Double.fromBits(input.i64())) }
                        }
                    }

                    else -> {
                        throw SnapshotException("unknown record type $type after $records records")
                    }
                }
            records++
            if (expireAt != Entry.NO_EXPIRY && db.now > expireAt) continue
            val entry = db.put(key, value)
            if (expireAt != Entry.NO_EXPIRY) db.setExpire(entry, expireAt)
            loaded++
        }
        val count = input.i64()
        val expectedCrc = input.crcSoFar()
        val crc = input.u32()
        if (count != records) throw SnapshotException("the end says $count records, the file holds $records")
        if (crc != expectedCrc) throw SnapshotException("checksum mismatch: the snapshot is damaged")
        return loaded
    }

    private fun typeOf(value: Any): Int =
        when (value) {
            is ByteArray -> STRING
            is HashValue -> HASH
            is ListValue -> LIST
            is SetValue -> SET
            is ZSetValue -> ZSET
            else -> error("no snapshot type for ${value::class}")
        }

    /**
     * A buffered writer that keeps the CRC and the count of what it wrote. As a [ByteSlice] it writes
     * the slice as a blob; as a [ScoredSlice], the member as a blob and then its score.
     */
    private class Out(
        private val sink: ByteSink,
    ) : ByteSlice,
        ScoredSlice {
        override fun accept(
            data: ByteArray,
            offset: Int,
            length: Int,
        ) {
            count(length)
            bytes(data, offset, length)
        }

        override fun accept(
            data: ByteArray,
            offset: Int,
            length: Int,
            score: Double,
        ) {
            accept(data, offset, length)
            i64(score.toRawBits())
        }

        val crc = Crc32()
        var written = 0L
        private val buffer = ByteArray(1 shl 20)
        private var at = 0

        fun u8(v: Int) {
            if (at == buffer.size) flush()
            buffer[at++] = v.toByte()
        }

        fun u32(v: Int) {
            for (shift in 24 downTo 0 step 8) u8(v ushr shift)
        }

        fun i64(v: Long) {
            for (shift in 56 downTo 0 step 8) u8((v ushr shift).toInt())
        }

        fun count(n: Int) {
            var v = n
            do {
                var b = v and 0x7f
                v = v ushr 7
                if (v != 0) b = b or 0x80
                u8(b)
            } while (v != 0)
        }

        fun blob(bytes: ByteArray) {
            count(bytes.size)
            bytes(bytes)
        }

        fun bytes(bytes: ByteArray) = bytes(bytes, 0, bytes.size)

        fun bytes(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            var from = offset
            val end = offset + length
            while (from < end) {
                if (at == buffer.size) flush()
                val n = minOf(end - from, buffer.size - at)
                bytes.copyInto(buffer, at, from, from + n)
                at += n
                from += n
            }
        }

        fun flush() {
            if (at == 0) return
            crc.update(buffer, 0, at)
            sink.write(buffer, 0, at)
            written += at
            at = 0
        }
    }

    /** A buffered reader that keeps the CRC of what it consumed. */
    private class In(
        private val source: ByteSource,
    ) {
        private val crc = Crc32()
        private val buffer = ByteArray(1 shl 20)
        private var at = 0
        private var end = 0

        /** How far into [buffer] the CRC has been taken. */
        private var checked = 0

        /** The CRC of every byte consumed so far. */
        fun crcSoFar(): Int {
            crc.update(buffer, checked, at - checked)
            checked = at
            return crc.value
        }

        private fun fill() {
            crc.update(buffer, checked, end - checked)
            checked = 0
            at = 0
            end = 0
            while (end == 0) {
                val n = source.read(buffer, 0, buffer.size)
                if (n < 0) throw SnapshotException("the snapshot ends early: it was torn or cut")
                end = n
            }
        }

        fun u8(): Int {
            if (at == end) fill()
            return buffer[at++].toInt() and 0xff
        }

        fun u32(): Int {
            var v = 0
            repeat(4) { v = (v shl 8) or u8() }
            return v
        }

        fun i64(): Long {
            var v = 0L
            repeat(8) { v = (v shl 8) or u8().toLong() }
            return v
        }

        fun count(): Int {
            var v = 0
            var shift = 0
            while (true) {
                val b = u8()
                v = v or ((b and 0x7f) shl shift)
                if (b and 0x80 == 0) return v
                shift += 7
                if (shift > 28) throw SnapshotException("a length does not fit: the snapshot is damaged")
            }
        }

        fun blob(): ByteArray = bytes(count())

        fun bytes(n: Int): ByteArray {
            val out = ByteArray(n)
            var filled = 0
            while (filled < n) {
                if (at == end) fill()
                val k = minOf(n - filled, end - at)
                buffer.copyInto(out, filled, at, at + k)
                at += k
                filled += k
            }
            return out
        }
    }
}
