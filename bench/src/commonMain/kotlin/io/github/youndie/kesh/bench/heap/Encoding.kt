package io.github.youndie.kesh.bench.heap

import io.github.youndie.kesh.bench.HashEntry
import io.github.youndie.kesh.bench.ListEntry
import io.github.youndie.kesh.bench.Rng
import io.github.youndie.kesh.bench.SetEntry
import io.github.youndie.kesh.bench.SortedSetEntry

/**
 * How a collection value is held in the heap — the one thing B-19 varies (research D-3, consequence 3
 * of §1.2: the collector marks objects, so the object count is the lever).
 *
 * Keys and string values are one `ByteArray` each in every encoding; only hashes, lists, sets and
 * sorted sets differ. Each `churn…` replaces or updates a value the way the matching write command
 * would, allocating what that command would allocate.
 */
sealed interface Encoding {
    val name: String

    fun hash(entry: HashEntry): Any

    fun list(entry: ListEntry): Any

    fun set(entry: SetEntry): Any

    fun sortedSet(entry: SortedSetEntry): Any

    /** `HSET key field value` on one of the hash's fields; returns the value to keep. */
    fun churnHash(
        value: Any,
        rng: Rng,
        newValue: ByteArray,
    ): Any

    /** `LPUSH key item` then `LTRIM key 0 len-1`; returns the value to keep. */
    fun churnList(
        value: Any,
        item: ByteArray,
    ): Any

    /** `ZINCRBY key delta member` on one member; returns the value to keep. */
    fun churnSortedSet(
        value: Any,
        rng: Rng,
    ): Any

    companion object {
        fun named(name: String): Encoding =
            when (name) {
                "naive" -> Naive
                "packed" -> Packed
                else -> error("encoding is naive or packed: $name")
            }
    }
}

/** A key of a Kotlin map or set: content equality over a `ByteArray`, which has none of its own. */
class Bytes(
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is Bytes && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()
}

/**
 * One object per element, the way a first Kotlin implementation would hold them: `HashMap` for
 * hashes, `ArrayDeque` for lists, `HashSet` for sets, and for a sorted set a member→node map plus one
 * node per member with a skiplist level array — the objects of Redis's `zset` (dict + skiplist). The
 * nodes are not linked: the probe measures what the collector marks, not ordering.
 */
object Naive : Encoding {
    override val name = "naive"

    class Node(
        val member: ByteArray,
        var score: Double,
        val forward: Array<Node?>,
    )

    class SortedSet(
        val index: HashMap<Bytes, Node>,
        val members: Array<Bytes>,
    )

    override fun hash(entry: HashEntry): Any =
        HashMap<Bytes, ByteArray>(entry.fields.size * 2).apply {
            entry.fields.forEach { (f, v) -> put(Bytes(f), v) }
        }

    override fun list(entry: ListEntry): Any = ArrayDeque(entry.items)

    override fun set(entry: SetEntry): Any =
        HashSet<Bytes>(entry.members.size * 2).apply {
            entry.members.forEach { add(Bytes(it)) }
        }

    override fun sortedSet(entry: SortedSetEntry): Any {
        val rng = Rng(entry.key.contentHashCode().toLong())
        val index = HashMap<Bytes, Node>(entry.members.size * 2)
        val members = Array(entry.members.size) { Bytes(entry.members[it]) }
        members.forEachIndexed {
            i,
            m,
            ->
            index[m] = Node(m.bytes, entry.scores[i].toDouble(), arrayOfNulls(level(rng)))
        }
        return SortedSet(index, members)
    }

    @Suppress("UNCHECKED_CAST")
    override fun churnHash(
        value: Any,
        rng: Rng,
        newValue: ByteArray,
    ): Any {
        val map = value as HashMap<Bytes, ByteArray>
        val field = map.keys.elementAt(rng.nextInt(0, map.size))
        map[field] = newValue
        return map
    }

    @Suppress("UNCHECKED_CAST")
    override fun churnList(
        value: Any,
        item: ByteArray,
    ): Any {
        val deque = value as ArrayDeque<ByteArray>
        deque.addFirst(item)
        deque.removeLast()
        return deque
    }

    /** Redis's `ZINCRBY` removes the node and inserts a new one; so does this. */
    override fun churnSortedSet(
        value: Any,
        rng: Rng,
    ): Any {
        val set = value as SortedSet
        val member = set.members[rng.nextInt(0, set.members.size)]
        val old = set.index.getValue(member)
        set.index[member] = Node(old.member, old.score + 1, arrayOfNulls(level(rng)))
        return set
    }

    /** Redis's `zslRandomLevel`: p = 1/4, at most 32. */
    private fun level(rng: Rng): Int {
        var level = 1
        while (level < 32 && rng.nextDouble() < 0.25) level++
        return level
    }
}

/**
 * Everything that can be one flat array is one. A hash, a list or a set is a single `ByteArray` of
 * length-prefixed elements (every one in the reference dataset is small, as Redis's listpack would
 * hold it); a sorted set is five objects whatever its size — members in one arena, their offsets,
 * their scores, and an open-addressing index over them. The floor of what D-3 allows: plain Kotlin
 * arrays, no off-heap memory, no manual lifetimes.
 */
object Packed : Encoding {
    override val name = "packed"

    class SortedSet(
        val arena: ByteArray,
        val offsets: IntArray,
        val scores: DoubleArray,
        val index: IntArray,
    )

    override fun hash(entry: HashEntry): Any = pack(entry.fields.flatMap { listOf(it.first, it.second) })

    override fun list(entry: ListEntry): Any = pack(entry.items)

    override fun set(entry: SetEntry): Any = pack(entry.members)

    override fun sortedSet(entry: SortedSetEntry): Any {
        val n = entry.members.size
        val offsets = IntArray(n + 1)
        for (i in 0 until n) offsets[i + 1] = offsets[i] + entry.members[i].size
        val arena = ByteArray(offsets[n])
        entry.members.forEachIndexed { i, m -> m.copyInto(arena, offsets[i]) }
        val index = IntArray(Integer2.powerOfTwo(n * 2)) { -1 }
        for (i in 0 until n) {
            var slot = entry.members[i].contentHashCode() and (index.size - 1)
            while (index[slot] >= 0) slot = (slot + 1) and (index.size - 1)
            index[slot] = i
        }
        return SortedSet(arena, offsets, DoubleArray(n) { entry.scores[it].toDouble() }, index)
    }

    /** Rewrites the whole packed hash with one value replaced — what a listpack `HSET` costs. */
    override fun churnHash(
        value: Any,
        rng: Rng,
        newValue: ByteArray,
    ): Any {
        val elements = unpack(value as ByteArray).toMutableList()
        val field = rng.nextInt(0, elements.size / 2)
        elements[field * 2 + 1] = newValue
        return pack(elements)
    }

    override fun churnList(
        value: Any,
        item: ByteArray,
    ): Any {
        val items = unpack(value as ByteArray)
        return pack(listOf(item) + items.dropLast(1))
    }

    /** A score changes in place; the order it would move to is a CPU cost, not a heap one. */
    override fun churnSortedSet(
        value: Any,
        rng: Rng,
    ): Any {
        val set = value as SortedSet
        set.scores[rng.nextInt(0, set.scores.size)] += 1.0
        return set
    }

    /** `[count: u16][len: u16][bytes]…` — every element of the reference dataset is under 64 KB. */
    fun pack(elements: List<ByteArray>): ByteArray {
        val out = ByteArray(2 + elements.sumOf { 2 + it.size })
        out.u16(0, elements.size)
        var pos = 2
        for (e in elements) {
            out.u16(pos, e.size)
            e.copyInto(out, pos + 2)
            pos += 2 + e.size
        }
        return out
    }

    fun unpack(packed: ByteArray): List<ByteArray> {
        val count = packed.u16(0)
        var pos = 2
        return List(count) {
            val length = packed.u16(pos)
            packed.copyOfRange(pos + 2, pos + 2 + length).also { pos += 2 + length }
        }
    }

    private fun ByteArray.u16(
        at: Int,
        value: Int,
    ) {
        this[at] = (value ushr 8).toByte()
        this[at + 1] = value.toByte()
    }

    private fun ByteArray.u16(at: Int): Int = ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)

    private object Integer2 {
        fun powerOfTwo(n: Int): Int {
            var p = 1
            while (p < n) p = p shl 1
            return p
        }
    }
}
