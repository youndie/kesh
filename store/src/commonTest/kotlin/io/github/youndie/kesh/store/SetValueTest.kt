package io.github.youndie.kesh.store

import io.github.youndie.kesh.store.sets.SetValue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The packed encoding and where it ends: Redis 7.2's listpack limits for sets. */
class SetValueTest {
    private fun b(s: String) = s.encodeToByteArray()

    @Test
    fun `members are found by content and held once`() {
        val set = SetValue(seed = 1)
        assertTrue(set.add(b("a")))
        assertFalse(set.add(b("a")))
        assertTrue(set.contains(b("a")))
        assertTrue(set.remove(b("a")))
        assertFalse(set.remove(b("a")))
        assertEquals(0, set.size)
    }

    @Test
    fun `the 129th member converts and nothing converts back`() {
        val set = SetValue(seed = 1)
        repeat(128) { set.add(b("m$it")) }
        assertTrue(set.isPacked)
        set.add(b("m128"))
        assertFalse(set.isPacked)
        repeat(120) { set.remove(b("m$it")) }
        assertFalse(set.isPacked)
        assertEquals(9, set.size)
        assertTrue(set.contains(b("m128")))
    }

    @Test
    fun `a member longer than 64 bytes converts and 64 does not`() {
        val set = SetValue(seed = 1)
        set.add(ByteArray(64) { 1 })
        assertTrue(set.isPacked)
        set.add(ByteArray(65) { 1 })
        assertFalse(set.isPacked)
        assertEquals(2, set.size)
    }

    @Test
    fun `a random member is a member in both encodings`() {
        val small = SetValue(seed = 1).apply { repeat(5) { add(b("s$it")) } }
        val large = SetValue(seed = 1).apply { repeat(500) { add(b("l$it")) } }
        val random = Random(42)
        repeat(200) {
            assertTrue(small.contains(small.random(random)))
            assertTrue(large.contains(large.random(random)))
        }
        assertEquals(
            5,
            (0 until 200).map { small.random(random).decodeToString() }.toSet().size,
            "every member comes up",
        )
    }
}
