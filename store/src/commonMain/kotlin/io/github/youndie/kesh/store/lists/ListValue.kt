package io.github.youndie.kesh.store.lists

import io.github.youndie.kesh.store.memory.MemoryModel
import io.github.youndie.kesh.store.packed.Packed

/**
 * A list: a deque of **chunks**, each one packed `ByteArray` of items (the [Packed] layout) — Redis's
 * quicklist of listpacks in role. The collector marks two objects per chunk instead of one per item
 * (research §1.2, B-19: for memory), and a feed of 20–200 ids is one or a few chunks.
 *
 * A chunk holds up to [maxChunkBytes] of packed items, Redis 7.2's `list-max-listpack-size -2`
 * (8 KiB); an item bigger than that sits in a chunk of its own, as Redis's plain nodes do. Unlike a
 * hash's, a list's encoding cannot show in any reply — every reply is in list order — so the size is
 * a memory choice only (research D-21).
 *
 * Indexes here are already normalised: `0 until size`. Store thread only.
 */
class ListValue {
    /** A chunk. Inner, so that replacing its bytes keeps the list's running total ([chunkBytes]). */
    private inner class Chunk(
        bytes: ByteArray,
        var count: Int,
    ) {
        var bytes: ByteArray = bytes
            set(value) {
                chunkBytes += MemoryModel.array(value.size.toLong()) - MemoryModel.array(field.size.toLong())
                field = value
            }

        init {
            chunkBytes += CHUNK + MemoryModel.array(bytes.size.toLong())
        }

        /** Takes this chunk's cost off the total, when it leaves the list. */
        fun dropped() {
            chunkBytes -= CHUNK + MemoryModel.array(bytes.size.toLong())
        }
    }

    /** Running total of the chunks: their objects and byte arrays (research D-10). */
    private var chunkBytes = 0L

    private val chunks = ArrayDeque<Chunk>()

    /** The list's estimated size: this object, its deque's array, its chunks. */
    val estimatedBytes: Long get() = SELF + MemoryModel.array(MemoryModel.WORD * chunks.size) + chunkBytes

    /** [estimatedBytes] summed from scratch, for tests. */
    internal fun recountBytes(): Long =
        SELF + MemoryModel.array(MemoryModel.WORD * chunks.size) +
            chunks.sumOf { CHUNK + MemoryModel.array(it.bytes.size.toLong()) }

    var size: Int = 0
        private set

    /** How many chunks hold the items; for tests. */
    val chunkCount: Int get() = chunks.size

    fun pushFirst(item: ByteArray) {
        val encoded = Packed.encode(item)
        val head = chunks.firstOrNull()
        if (head != null && fits(head, encoded)) {
            head.bytes = encoded + head.bytes
            head.count++
        } else {
            chunks.addFirst(Chunk(encoded, 1))
        }
        size++
    }

    fun pushLast(item: ByteArray) {
        val encoded = Packed.encode(item)
        val tail = chunks.lastOrNull()
        if (tail != null && fits(tail, encoded)) {
            tail.bytes = tail.bytes + encoded
            tail.count++
        } else {
            chunks.addLast(Chunk(encoded, 1))
        }
        size++
    }

    /** The item at [index]. */
    fun get(index: Int): ByteArray {
        val (c, position) = locateIndex(index)
        val bytes = chunks[c].bytes
        return Packed.read(bytes, Packed.skip(bytes, 0, position))
    }

    /** Replaces the item at [index]; a chunk it makes too big is split. */
    fun set(
        index: Int,
        item: ByteArray,
    ) {
        val (c, position) = locateIndex(index)
        val chunk = chunks[c]
        val at = Packed.skip(chunk.bytes, 0, position)
        chunk.bytes = Packed.splice(chunk.bytes, at, Packed.skip(chunk.bytes, at), Packed.encode(item))
        if (chunk.bytes.size > maxChunkBytes && chunk.count > 1) {
            chunks.removeAt(c).dropped()
            chunks.addAll(c, rechunk(items(chunk)))
        }
    }

    /** The items from [from] to [to], both included, in list order. */
    fun range(
        from: Int,
        to: Int,
    ): List<ByteArray> {
        val out = ArrayList<ByteArray>(to - from + 1)
        var (c, position) = locateIndex(from)
        var remaining = to - from + 1
        while (remaining > 0) {
            val chunk = chunks[c]
            var at = Packed.skip(chunk.bytes, 0, position)
            while (position < chunk.count && remaining > 0) {
                out.add(Packed.read(chunk.bytes, at))
                at = Packed.skip(chunk.bytes, at)
                position++
                remaining--
            }
            c++
            position = 0
        }
        return out
    }

    /** Drops the first [n] items: whole chunks where it can, then one cut. */
    fun removeFirst(n: Int) {
        var left = minOf(n, size)
        size -= left
        while (left > 0) {
            val head = chunks.first()
            if (head.count <= left) {
                left -= head.count
                chunks.removeFirst().dropped()
            } else {
                head.bytes = head.bytes.copyOfRange(Packed.skip(head.bytes, 0, left), head.bytes.size)
                head.count -= left
                left = 0
            }
        }
    }

    /** Drops the last [n] items: whole chunks where it can, then one cut. */
    fun removeLast(n: Int) {
        var left = minOf(n, size)
        size -= left
        while (left > 0) {
            val tail = chunks.last()
            if (tail.count <= left) {
                left -= tail.count
                chunks.removeLast().dropped()
            } else {
                tail.bytes = tail.bytes.copyOfRange(0, Packed.skip(tail.bytes, 0, tail.count - left))
                tail.count -= left
                left = 0
            }
        }
    }

    /**
     * `LREM`: removes up to [limit] items equal to [element] (every one when [limit] is 0), from the
     * head, or from the tail when [fromTail]. Only the chunks that lose an item are rewritten.
     */
    fun removeMatching(
        element: ByteArray,
        limit: Long,
        fromTail: Boolean,
    ): Long {
        var removed = 0L
        val order = if (fromTail) chunks.indices.reversed() else chunks.indices
        for (c in order) {
            if (limit != 0L && removed == limit) break
            val chunk = chunks[c]
            val items = items(chunk)
            val keep = BooleanArray(items.size) { true }
            val positions = if (fromTail) items.indices.reversed() else items.indices
            for (i in positions) {
                if (limit != 0L && removed == limit) break
                if (items[i].contentEquals(element)) {
                    keep[i] = false
                    removed++
                }
            }
            val kept = items.filterIndexed { i, _ -> keep[i] }
            if (kept.size != items.size) {
                size -= items.size - kept.size
                chunk.bytes = kept.fold(Packed.EMPTY) { bytes, item -> bytes + Packed.encode(item) }
                chunk.count = kept.size
            }
        }
        chunks.removeAll { chunk -> (chunk.count == 0).also { if (it) chunk.dropped() } }
        return removed
    }

    private fun fits(
        chunk: Chunk,
        encoded: ByteArray,
    ): Boolean = chunk.bytes.size + encoded.size <= maxChunkBytes

    /** The chunk holding [index] and the item's position in it, walking from the nearer end. */
    private fun locateIndex(index: Int): Pair<Int, Int> {
        require(index in 0 until size) { "index $index outside 0 until $size" }
        if (index < size / 2) {
            var skipped = 0
            for (c in chunks.indices) {
                val count = chunks[c].count
                if (index < skipped + count) return c to index - skipped
                skipped += count
            }
        } else {
            var after = size
            for (c in chunks.indices.reversed()) {
                after -= chunks[c].count
                if (index >= after) return c to index - after
            }
        }
        error("unreachable: $index in a list of $size")
    }

    private fun items(chunk: Chunk): List<ByteArray> {
        val out = ArrayList<ByteArray>(chunk.count)
        var at = 0
        repeat(chunk.count) {
            out.add(Packed.read(chunk.bytes, at))
            at = Packed.skip(chunk.bytes, at)
        }
        return out
    }

    private fun rechunk(items: List<ByteArray>): List<Chunk> {
        val out = ArrayList<Chunk>()
        for (item in items) {
            val encoded = Packed.encode(item)
            val last = out.lastOrNull()
            if (last != null && fits(last, encoded)) {
                last.bytes = last.bytes + encoded
                last.count++
            } else {
                out.add(Chunk(encoded, 1))
            }
        }
        return out
    }

    companion object {
        /** This object: header, deque, size, running total. */
        private const val SELF = MemoryModel.OBJECT + 3 * MemoryModel.WORD

        /** A chunk object: header, bytes, count, the reference to its list. */
        private const val CHUNK = MemoryModel.OBJECT + 3 * MemoryModel.WORD

        /** `list-max-listpack-size -2`: 8 KiB of packed items per chunk, Redis 7.2's default. */
        var maxChunkBytes: Int = 8 * 1024
    }
}
