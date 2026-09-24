package io.github.youndie.kesh.store

import io.github.youndie.kesh.store.hashes.HashValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The two encodings and the boundary between them, which clients see only as reply order. */
class HashValueTest {
    private fun b(s: String) = s.encodeToByteArray()

    private fun pairs(hash: HashValue): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        hash.forEach { f, v -> out.add(f.decodeToString() to v.decodeToString()) }
        return out
    }

    @Test
    fun `a packed hash keeps insertion order through updates and deletes`() {
        val hash = HashValue(seed = 1)
        assertTrue(hash.set(b("a"), b("1")))
        assertTrue(hash.set(b("b"), b("2")))
        assertTrue(hash.set(b("c"), b("3")))
        assertFalse(hash.set(b("b"), b("twenty")))
        assertTrue(hash.delete(b("a")))
        assertFalse(hash.delete(b("a")))
        assertEquals(listOf("b" to "twenty", "c" to "3"), pairs(hash))
        assertEquals("twenty", hash.get(b("b"))!!.decodeToString())
        assertNull(hash.get(b("a")))
        assertTrue(hash.isPacked)
    }

    @Test
    fun `the 513th field converts and nothing converts back`() {
        val hash = HashValue(seed = 1)
        repeat(512) { hash.set(b("f$it"), b("v$it")) }
        assertTrue(hash.isPacked, "512 fields still pack")
        hash.set(b("f512"), b("v512"))
        assertFalse(hash.isPacked, "the 513th converts")
        repeat(500) { hash.delete(b("f$it")) }
        assertFalse(hash.isPacked, "Redis 7.2 never converts back")
        assertEquals(13, hash.size)
        assertEquals("v512", hash.get(b("f512"))!!.decodeToString())
    }

    @Test
    fun `a field or value longer than 64 bytes converts and 64 does not`() {
        val hash = HashValue(seed = 1)
        hash.set(b("f"), ByteArray(64) { 'x'.code.toByte() })
        hash.set(ByteArray(64) { 'k'.code.toByte() }, b("v"))
        assertTrue(hash.isPacked)
        hash.set(b("g"), ByteArray(65) { 'x'.code.toByte() })
        assertFalse(hash.isPacked)
        assertEquals(3, hash.size)
    }

    @Test
    fun `a write is checked before it happens as hashTypeTryConversion does`() {
        val long = HashValue(seed = 1)
        long.prepareFor(listOf(b("hset"), b("k"), b("f"), ByteArray(65)), from = 2)
        assertFalse(long.isPacked, "one long argument converts even an empty hash")
        val many = HashValue(seed = 1)
        many.prepareFor(listOf(b("hset"), b("k")) + (0 until 1026).map { b("x$it") }, from = 2)
        assertFalse(many.isPacked, "513 pairs in one command convert before the first is written")
        val few = HashValue(seed = 1)
        few.prepareFor(listOf(b("hset"), b("k")) + (0 until 1024).map { b("x$it") }, from = 2)
        assertTrue(few.isPacked)
    }

    @Test
    fun `a converted hash holds everything it held packed`() {
        val hash = HashValue(seed = 1)
        repeat(600) { hash.set(b("f$it"), b("v$it")) }
        assertEquals(600, hash.size)
        repeat(600) { assertEquals("v$it", hash.get(b("f$it"))!!.decodeToString()) }
        assertEquals(600, pairs(hash).toSet().size)
    }
}
