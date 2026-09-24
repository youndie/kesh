package io.github.youndie.kesh.store

import io.github.youndie.kesh.store.keyspace.Keyspace
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyspaceTest {
    private fun key(i: Int) = "key:$i".encodeToByteArray()

    @Test
    fun `keys are found by content and not by identity`() {
        val keyspace = Keyspace()
        keyspace.put("k".encodeToByteArray(), "v")
        assertEquals("v", keyspace.get("k".encodeToByteArray())!!.value)
    }

    @Test
    fun `growth moves a bounded number of buckets per operation and loses nothing`() {
        val keyspace = Keyspace(seed = 7)
        var maxMoved = 0
        var rehashes = 0
        var wasRehashing = false
        repeat(1_000_000) { i ->
            keyspace.put(key(i), i)
            maxMoved = maxOf(maxMoved, keyspace.bucketsMovedLastStep)
            if (keyspace.isRehashing && !wasRehashing) rehashes++
            wasRehashing = keyspace.isRehashing
        }
        assertEquals(1_000_000, keyspace.size)
        assertTrue(maxMoved <= Keyspace.REHASH_BUCKETS_PER_STEP, "an operation moved $maxMoved buckets")
        assertTrue(rehashes >= 15, "only $rehashes resizes for a million keys")
        repeat(1_000_000) { i -> assertEquals(i, keyspace.get(key(i))!!.value, "key $i") }
    }

    @Test
    fun `entries can be removed while the table is rehashing`() {
        val keyspace = Keyspace()
        repeat(1000) { keyspace.put(key(it), it) }
        repeat(1000) { if (it % 2 == 0) keyspace.remove(key(it)) }
        assertEquals(500, keyspace.size)
        repeat(1000) { i -> if (i % 2 == 0) assertNull(keyspace.get(key(i))) else assertEquals(i, keyspace.get(key(i))!!.value) }
    }

    @Test
    fun `a random entry is one of the entries`() {
        val keyspace = Keyspace()
        repeat(100) { keyspace.put(key(it), it) }
        val random = Random(1)
        repeat(1000) { assertTrue(keyspace.randomEntry { random.nextInt(it) }!!.value as Int in 0 until 100) }
        assertNull(Keyspace().randomEntry { random.nextInt(it) })
    }

    @Test
    fun `every entry is visited once`() {
        val keyspace = Keyspace()
        repeat(10_000) { keyspace.put(key(it), it) }
        val seen = HashSet<Int>()
        keyspace.forEach { assertTrue(seen.add(it.value as Int)) }
        assertEquals(10_000, seen.size)
    }
}
