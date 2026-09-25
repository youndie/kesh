package io.github.youndie.kesh.store.zsets

import kotlin.random.Random

/**
 * Redis's skiplist with spans (`redis/redis@7.2!/src/t_zset.c` — `zslInsert`, `zslDelete`,
 * `zslGetRank`, `zslGetElementByRank`), ordered by score and then by member bytes: rank and position
 * both logarithmic.
 *
 * **Objects per member** (research D-23): a [Node] and its member's `ByteArray`; the level arrays
 * exist only on nodes taller than one level — a quarter of them, as the level is drawn with p = 1/4.
 * The first level lives in the node's own fields.
 */
internal class SkipList(
    private val random: Random = Random.Default,
) {
    class Node(
        val member: ByteArray,
        var score: Double,
        level: Int,
    ) {
        var backward: Node? = null
        internal var next0: Node? = null
        internal var span0: Int = 0
        private val upper: Array<Node?>? = if (level > 1) arrayOfNulls(level - 1) else null
        private val upperSpan: IntArray? = if (level > 1) IntArray(level - 1) else null

        val level: Int get() = 1 + (upper?.size ?: 0)

        /** The next node in list order. */
        val next: Node? get() = next0

        internal fun next(i: Int): Node? = if (i == 0) next0 else upper!![i - 1]

        internal fun setNext(
            i: Int,
            node: Node?,
        ) {
            if (i == 0) next0 = node else upper!![i - 1] = node
        }

        internal fun span(i: Int): Int = if (i == 0) span0 else upperSpan!![i - 1]

        internal fun setSpan(
            i: Int,
            span: Int,
        ) {
            if (i == 0) span0 = span else upperSpan!![i - 1] = span
        }
    }

    private val header = Node(ByteArray(0), 0.0, MAX_LEVEL)
    private var level = 1
    private var tail: Node? = null

    var size: Int = 0
        private set

    val first: Node? get() = header.next0
    val last: Node? get() = tail

    fun insert(
        member: ByteArray,
        score: Double,
    ): Node {
        val update = arrayOfNulls<Node>(MAX_LEVEL)
        val rank = IntArray(MAX_LEVEL)
        var x = header
        for (i in level - 1 downTo 0) {
            rank[i] = if (i == level - 1) 0 else rank[i + 1]
            while (true) {
                val next = x.next(i) ?: break
                if (!before(next, score, member)) break
                rank[i] += x.span(i)
                x = next
            }
            update[i] = x
        }
        val newLevel = randomLevel()
        if (newLevel > level) {
            for (i in level until newLevel) {
                rank[i] = 0
                update[i] = header
                header.setSpan(i, size)
            }
            level = newLevel
        }
        val node = Node(member, score, newLevel)
        for (i in 0 until newLevel) {
            val u = update[i]!!
            node.setNext(i, u.next(i))
            u.setNext(i, node)
            node.setSpan(i, u.span(i) - (rank[0] - rank[i]))
            u.setSpan(i, rank[0] - rank[i] + 1)
        }
        for (i in newLevel until level) update[i]!!.setSpan(i, update[i]!!.span(i) + 1)
        node.backward = if (update[0] === header) null else update[0]
        val after = node.next0
        if (after != null) after.backward = node else tail = node
        size++
        return node
    }

    /** Removes [node], which must be in this list. */
    fun delete(node: Node) {
        val update = arrayOfNulls<Node>(MAX_LEVEL)
        var x = header
        for (i in level - 1 downTo 0) {
            while (true) {
                val next = x.next(i) ?: break
                if (!before(next, node.score, node.member)) break
                x = next
            }
            update[i] = x
        }
        check(x.next0 === node) { "the node is not in the list" }
        for (i in 0 until level) {
            val u = update[i]!!
            if (u.next(i) === node) {
                u.setSpan(i, u.span(i) + node.span(i) - 1)
                u.setNext(i, node.next(i))
            } else {
                u.setSpan(i, u.span(i) - 1)
            }
        }
        val after = node.next0
        if (after != null) after.backward = node.backward else tail = node.backward
        while (level > 1 && header.next(level - 1) == null) level--
        size--
    }

    /** The 0-based rank of [node], which must be in this list. */
    fun rank(node: Node): Int {
        var traversed = 0
        var x = header
        for (i in level - 1 downTo 0) {
            while (true) {
                val next = x.next(i) ?: break
                if (before(next, node.score, node.member) || next === node) {
                    traversed += x.span(i)
                    x = next
                    if (x === node) return traversed - 1
                } else {
                    break
                }
            }
        }
        error("the node is not in the list")
    }

    /** The node at 0-based [rank], which must be in `0 until size`. */
    fun at(rank: Int): Node {
        require(rank in 0 until size) { "rank $rank outside 0 until $size" }
        val target = rank + 1
        var traversed = 0
        var x = header
        for (i in level - 1 downTo 0) {
            while (true) {
                val next = x.next(i) ?: break
                if (traversed + x.span(i) > target) break
                traversed += x.span(i)
                x = next
            }
            if (traversed == target) return x
        }
        error("unreachable: rank $rank of $size")
    }

    /**
     * How many nodes come first under [precedes] — a predicate that holds for a prefix of the list
     * and for nothing after it. A score or member bound becomes a rank this way, in logarithmic time.
     */
    fun countWhile(precedes: (Node) -> Boolean): Int {
        var traversed = 0
        var x = header
        for (i in level - 1 downTo 0) {
            while (true) {
                val next = x.next(i) ?: break
                if (!precedes(next)) break
                traversed += x.span(i)
                x = next
            }
        }
        return traversed
    }

    private fun randomLevel(): Int {
        var level = 1
        while (level < MAX_LEVEL && random.nextInt(4) == 0) level++
        return level
    }

    companion object {
        /** `ZSKIPLIST_MAXLEVEL`. */
        const val MAX_LEVEL = 32

        /** Whether [node] sorts before ([score], [member]): by score, then by member bytes. */
        fun before(
            node: Node,
            score: Double,
            member: ByteArray,
        ): Boolean = node.score < score || (node.score == score && compareBytes(node.member, member) < 0)

        /** `sdscmp`: unsigned bytes, then length. */
        fun compareBytes(
            a: ByteArray,
            b: ByteArray,
        ): Int {
            val n = minOf(a.size, b.size)
            for (i in 0 until n) {
                val c = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
                if (c != 0) return c
            }
            return a.size - b.size
        }
    }
}
