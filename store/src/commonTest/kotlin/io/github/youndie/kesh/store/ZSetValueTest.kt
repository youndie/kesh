package io.github.youndie.kesh.store

import io.github.youndie.kesh.store.zsets.ZSetValue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Both encodings held against a sorted reference list, under random writes. */
class ZSetValueTest {
    private data class Item(
        val member: String,
        val score: Double,
    )

    private val order = compareBy<Item>({ it.score }).thenComparator { a, b -> compareBytes(a.member, b.member) }

    private fun compareBytes(
        a: String,
        b: String,
    ): Int {
        val x = a.encodeToByteArray()
        val y = b.encodeToByteArray()
        for (i in 0 until minOf(x.size, y.size)) {
            val c = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
            if (c != 0) return c
        }
        return x.size - y.size
    }

    private fun check(
        zset: ZSetValue,
        reference: Map<String, Double>,
    ) {
        val sorted = reference.map { Item(it.key, it.value) }.sortedWith(order)
        assertEquals(sorted.size, zset.size)
        if (sorted.isEmpty()) return
        val all = zset.range(0, zset.size - 1).map { Item(it.first.decodeToString(), it.second) }
        assertEquals(sorted, all)
        assertEquals(
            sorted.reversed(),
            zset.range(0, zset.size - 1, reverse = true).map {
                Item(it.first.decodeToString(), it.second)
            },
        )
        sorted.forEachIndexed { rank, item ->
            if (rank % 7 == 0) {
                assertEquals(rank, zset.rank(item.member.encodeToByteArray()), item.member)
                assertEquals(item.score, zset.score(item.member.encodeToByteArray()))
            }
        }
        val probe = sorted[sorted.size / 2].score
        assertEquals(sorted.count { it.score < probe }, zset.countBelowScore(probe, orEqual = false))
        assertEquals(sorted.count { it.score <= probe }, zset.countBelowScore(probe, orEqual = true))
    }

    private fun churn(
        size: Int,
        steps: Int,
    ) {
        val random = Random(size)
        val zset = ZSetValue(seed = 1)
        val reference = HashMap<String, Double>()
        repeat(steps) { step ->
            val member = "m" + random.nextInt(size)
            when (random.nextInt(4)) {
                0 -> {
                    zset.remove(member.encodeToByteArray())
                    reference.remove(member)
                }

                else -> {
                    val score = random.nextInt(size / 4 + 1).toDouble()
                    zset.put(member.encodeToByteArray(), score)
                    reference[member] = score
                }
            }
            if (step % 500 == 0) check(zset, reference)
        }
        check(zset, reference)
        if (reference.isNotEmpty()) {
            val removed = zset.removeRange(1, minOf(10, zset.size - 1))
            val sorted = reference.map { Item(it.key, it.value) }.sortedWith(order)
            sorted.drop(1).take(removed).forEach { reference.remove(it.member) }
            check(zset, reference)
        }
    }

    @Test
    fun `a packed set stays in order under random writes`() = churn(size = 100, steps = 3_000)

    @Test
    fun `a skiplist stays in order and its ranks hold under random writes`() = churn(size = 5_000, steps = 20_000)

    @Test
    fun `the 129th member converts and nothing converts back`() {
        val zset = ZSetValue(seed = 1)
        repeat(128) { zset.put("m$it".encodeToByteArray(), it.toDouble()) }
        assertTrue(zset.isPacked)
        zset.put("m128".encodeToByteArray(), 128.0)
        assertFalse(zset.isPacked)
        repeat(120) { zset.remove("m$it".encodeToByteArray()) }
        assertFalse(zset.isPacked)
        assertEquals(9, zset.size)
    }

    @Test
    fun `a member longer than 64 bytes converts`() {
        val zset = ZSetValue(seed = 1)
        zset.put(ByteArray(64) { 1 }, 1.0)
        assertTrue(zset.isPacked)
        zset.put(ByteArray(65) { 1 }, 2.0)
        assertFalse(zset.isPacked)
    }
}
