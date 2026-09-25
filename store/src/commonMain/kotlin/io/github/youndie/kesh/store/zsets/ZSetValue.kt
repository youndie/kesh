package io.github.youndie.kesh.store.zsets

import io.github.youndie.kesh.store.keyspace.Keyspace
import io.github.youndie.kesh.store.memory.MemoryModel
import io.github.youndie.kesh.store.packed.Packed

/**
 * A sorted set: members ordered by score, then by member bytes. Two encodings, as Redis has
 * (research D-23):
 *
 * - **packed** — one `ByteArray` of (8-byte score, length-prefixed member) entries, kept sorted,
 *   while it has at most [maxPackedEntries] members of at most [maxPackedValue] bytes (Redis 7.2's
 *   `zset-max-listpack-entries 128`, `zset-max-listpack-value 64`);
 * - **skiplist** — a [SkipList] for order and rank, and a [Keyspace] from member to node for
 *   `ZSCORE` and lookups, once either limit is passed; for good.
 *
 * Every reply of a sorted set is in its order, so the encoding never shows. The interface is by
 * **rank** (0-based, ascending): score and lexical bounds become ranks ([countBelowScore],
 * [countBelowMember]) and every range command works on a rank range.
 */
class ZSetValue(
    private val seed: Int,
) {
    private var packed: ByteArray = Packed.EMPTY
    private var packedCount = 0
    private var list: SkipList? = null

    /** Member to [SkipList.Node], once the set is no longer packed; `ZSCAN` walks it. */
    internal var index: Keyspace? = null
        private set

    val size: Int get() = list?.size ?: packedCount

    /**
     * The sorted set's estimated size (research D-10): this object, and its packed bytes — or its
     * skiplist's nodes and members, an index entry per member, and the index's buckets.
     */
    val estimatedBytes: Long
        get() {
            val l = list ?: return SELF + MemoryModel.array(packed.size.toLong())
            return SELF + l.bytes + l.size * MemoryModel.ENTRY + MemoryModel.buckets(index!!.capacity)
        }

    /** [estimatedBytes] summed from scratch, for tests. */
    internal fun recountBytes(): Long {
        val l = list ?: return estimatedBytes
        var sum = 0L
        index!!.forEach { sum += SkipList.nodeBytes(it.value as SkipList.Node) + MemoryModel.ENTRY }
        return SELF + sum + MemoryModel.buckets(index!!.capacity)
    }

    /** Whether the set is still packed. Invisible to clients; for tests. */
    val isPacked: Boolean get() = list == null

    fun score(member: ByteArray): Double? {
        index?.let { return (it.get(member)?.value as SkipList.Node?)?.score }
        val at = findPacked(member)
        return if (at < 0) null else scoreAt(at)
    }

    /** The 0-based ascending rank of [member], or `null`. */
    fun rank(member: ByteArray): Int? {
        index?.let { i ->
            val node = i.get(member)?.value as SkipList.Node? ?: return null
            return list!!.rank(node)
        }
        var at = 0
        var rank = 0
        while (at < packed.size) {
            if (Packed.matches(packed, at + 8, member)) return rank
            at = Packed.skip(packed, at + 8)
            rank++
        }
        return null
    }

    /**
     * Sets [member]'s score, adding it if absent. `zsetAdd`'s conversions: a member too long to pack,
     * or one member too many, converts before the write.
     */
    fun put(
        member: ByteArray,
        score: Double,
    ) {
        if (list == null && findPacked(member) < 0 &&
            (packedCount + 1 > maxPackedEntries || member.size > maxPackedValue)
        ) {
            convert()
        }
        list?.let { l ->
            val entry = index!!.get(member)
            if (entry != null) {
                val node = entry.value as SkipList.Node
                if (node.score == score) return
                l.delete(node)
            }
            index!!.put(member, l.insert(member, score))
            return
        }
        val at = findPacked(member)
        if (at >= 0) {
            // `score != curscore` in `zsetAdd`: an equal score, -0 against 0 included, changes nothing.
            if (scoreAt(at) == score) return
            removePacked(member)
        }
        insertPacked(member, score)
    }

    /** Removes [member]; `true` if it was there. A sorted set never goes back to packed. */
    fun remove(member: ByteArray): Boolean {
        index?.let { i ->
            val entry = i.remove(member) ?: return false
            list!!.delete(entry.value as SkipList.Node)
            return true
        }
        return removePacked(member)
    }

    /** `zsetTypeMaybeConvert`: a write of more members than a packed set holds converts first. */
    fun prepareFor(incoming: Int) {
        if (list == null && incoming > maxPackedEntries) convert()
    }

    /** The members from rank [from] to [to], both included, ascending — or descending if [reverse]. */
    fun range(
        from: Int,
        to: Int,
        reverse: Boolean = false,
    ): List<Pair<ByteArray, Double>> {
        if (from > to) return emptyList()
        val out = ArrayList<Pair<ByteArray, Double>>(to - from + 1)
        list?.let { l ->
            var node: SkipList.Node? = l.at(if (reverse) to else from)
            repeat(to - from + 1) {
                val n = node!!
                out.add(n.member to n.score)
                node = if (reverse) n.backward else n.next
            }
            return out
        }
        var at = 0
        var rank = 0
        while (at < packed.size && rank <= to) {
            val next = Packed.skip(packed, at + 8)
            if (rank >= from) out.add(Packed.read(packed, at + 8) to scoreAt(at))
            at = next
            rank++
        }
        if (reverse) out.reverse()
        return out
    }

    /** Removes the members from rank [from] to [to], both included; returns how many. */
    fun removeRange(
        from: Int,
        to: Int,
    ): Int {
        if (from > to) return 0
        val doomed = range(from, to).map { it.first }
        doomed.forEach { remove(it) }
        return doomed.size
    }

    /** How many members score below [bound] — or at most [bound] when [orEqual]: a range's first rank. */
    fun countBelowScore(
        bound: Double,
        orEqual: Boolean,
    ): Int {
        val precedes = { score: Double -> score < bound || (orEqual && score == bound) }
        list?.let { l -> return l.countWhile { precedes(it.score) } }
        var at = 0
        var count = 0
        while (at < packed.size && precedes(scoreAt(at))) {
            at = Packed.skip(packed, at + 8)
            count++
        }
        return count
    }

    /**
     * How many members sort before [bound] by bytes — or at most [bound] when [orEqual]. Meaningful,
     * as in Redis, when all scores are equal. [LexBound.Min] and [LexBound.Max] are Redis's `-` and
     * `+`: nothing, and everything, below.
     */
    fun countBelowMember(
        bound: LexBound,
        orEqual: Boolean,
    ): Int {
        val member =
            when (bound) {
                LexBound.Min -> return 0
                LexBound.Max -> return size
                is LexBound.Value -> bound.bytes
            }
        val precedes = { m: ByteArray ->
            val c = SkipList.compareBytes(m, member)
            c < 0 || (orEqual && c == 0)
        }
        list?.let { l -> return l.countWhile { precedes(it.member) } }
        var at = 0
        var count = 0
        while (at < packed.size) {
            if (!precedes(Packed.read(packed, at + 8))) break
            at = Packed.skip(packed, at + 8)
            count++
        }
        return count
    }

    private fun convert() {
        val l = SkipList()
        val i = Keyspace(seed)
        range(0, size - 1).forEach { (member, score) -> i.put(member, l.insert(member, score)) }
        list = l
        index = i
        packed = Packed.EMPTY
        packedCount = 0
    }

    private fun scoreAt(at: Int): Double {
        var bits = 0L
        for (k in 0 until 8) bits = (bits shl 8) or (packed[at + k].toLong() and 0xff)
        return Double.fromBits(bits)
    }

    private fun findPacked(member: ByteArray): Int {
        var at = 0
        while (at < packed.size) {
            if (Packed.matches(packed, at + 8, member)) return at
            at = Packed.skip(packed, at + 8)
        }
        return -1
    }

    private fun removePacked(member: ByteArray): Boolean {
        val at = findPacked(member)
        if (at < 0) return false
        packed = Packed.splice(packed, at, Packed.skip(packed, at + 8))
        packedCount--
        return true
    }

    private fun insertPacked(
        member: ByteArray,
        score: Double,
    ) {
        var at = 0
        while (at < packed.size) {
            val s = scoreAt(at)
            if (s > score || (s == score && SkipList.compareBytes(Packed.read(packed, at + 8), member) > 0)) break
            at = Packed.skip(packed, at + 8)
        }
        // `zzlInsertAt` stores an integral score as an integer (`double2ll`), which turns -0 into 0:
        // a packed set answers `0` where a skiplist keeps `-0`. So does this one.
        val bits = (if (score == 0.0) 0.0 else score).toRawBits()
        val entry = ByteArray(8) { k -> (bits ushr (56 - 8 * k)).toByte() } + Packed.encode(member)
        packed = Packed.splice(packed, at, at, entry)
        packedCount++
    }

    /** A lexical range bound: Redis's `-`, `+`, or a member. */
    sealed interface LexBound {
        data object Min : LexBound

        data object Max : LexBound

        class Value(
            val bytes: ByteArray,
        ) : LexBound
    }

    companion object {
        /** This object: header, seed, packed array, count, skiplist, index. */
        private const val SELF = MemoryModel.OBJECT + 5 * MemoryModel.WORD

        /** `zset-max-listpack-entries`, Redis 7.2's default. */
        var maxPackedEntries: Int = 128

        /** `zset-max-listpack-value`, Redis 7.2's default. */
        var maxPackedValue: Int = 64
    }
}
