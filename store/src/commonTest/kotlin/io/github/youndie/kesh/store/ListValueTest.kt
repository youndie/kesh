package io.github.youndie.kesh.store

import io.github.youndie.kesh.store.lists.ListValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Chunks: items are where the list says, across chunk boundaries, whatever cut them. */
class ListValueTest {
    private fun item(i: Int) = ("item:" + i.toString().padStart(6, '0')).encodeToByteArray()

    private fun ListValue.all() = if (size == 0) emptyList() else range(0, size - 1).map { it.decodeToString() }

    /** 2 000 items of 11 bytes make about 24 KiB: three chunks and more. */
    private fun filled(): ListValue = ListValue().apply { repeat(2000) { pushLast(item(it)) } }

    @Test
    fun `a long list spreads over chunks and reads back in order`() {
        val list = filled()
        assertTrue(list.chunkCount >= 3, "${list.chunkCount} chunks")
        assertEquals((0 until 2000).map { item(it).decodeToString() }, list.all())
        assertEquals("item:001234", list.get(1234).decodeToString())
        assertEquals("item:001999", list.get(1999).decodeToString())
    }

    @Test
    fun `pushes at the head build a reversed list`() {
        val list = ListValue()
        repeat(2000) { list.pushFirst(item(it)) }
        assertEquals((1999 downTo 0).map { item(it).decodeToString() }, list.all())
    }

    @Test
    fun `trimming both ends crosses chunk boundaries`() {
        val list = filled()
        list.removeFirst(900)
        list.removeLast(900)
        assertEquals((900 until 1100).map { item(it).decodeToString() }, list.all())
        list.removeFirst(1000)
        assertEquals(0, list.size)
        assertEquals(0, list.chunkCount)
    }

    @Test
    fun `LREM removes from the side it is told and no more than it is told`() {
        val list = ListValue()
        listOf("x", "a", "x", "b", "x", "c", "x").forEach { list.pushLast(it.encodeToByteArray()) }
        assertEquals(2L, list.removeMatching("x".encodeToByteArray(), 2, fromTail = true))
        assertEquals(listOf("x", "a", "x", "b", "c"), list.all())
        assertEquals(1L, list.removeMatching("x".encodeToByteArray(), 1, fromTail = false))
        assertEquals(listOf("a", "x", "b", "c"), list.all())
        assertEquals(1L, list.removeMatching("x".encodeToByteArray(), 0, fromTail = false))
        assertEquals(listOf("a", "b", "c"), list.all())
    }

    @Test
    fun `LREM across chunks drops the chunks it empties`() {
        val list = filled()
        repeat(2000) { list.set(it, if (it < 1500) "gone".encodeToByteArray() else item(it)) }
        assertEquals(1500L, list.removeMatching("gone".encodeToByteArray(), 0, fromTail = false))
        assertEquals((1500 until 2000).map { item(it).decodeToString() }, list.all())
        assertEquals(1, list.chunkCount)
    }

    @Test
    fun `an item bigger than a chunk sits alone`() {
        val list = ListValue()
        list.pushLast("a".encodeToByteArray())
        list.pushLast(ByteArray(10_000) { 1 })
        list.pushLast("b".encodeToByteArray())
        assertEquals(3, list.chunkCount)
        assertEquals(10_000, list.get(1).size)
    }

    @Test
    fun `a set that overfills a chunk splits it and keeps the order`() {
        val list = ListValue()
        repeat(10) { list.pushLast(item(it)) }
        assertEquals(1, list.chunkCount)
        list.set(4, ByteArray(8_150) { 2 })
        assertTrue(list.chunkCount >= 2, "${list.chunkCount} chunks")
        assertEquals(10, list.size)
        assertEquals(8_150, list.get(4).size)
        assertEquals((5 until 10).map { item(it).decodeToString() }, list.range(5, 9).map { it.decodeToString() })
        assertEquals("item:000003", list.get(3).decodeToString())
    }
}
