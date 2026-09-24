package io.github.youndie.kesh.store

import io.github.youndie.kesh.store.keyspace.Keyspace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// B-05's acceptance at the size it names: sixteen million keys, the bound asserted on the table after
// every insertion. On linuxX64 only, the target that ships: the entries take about 2 GB, more than the
// JVM test heap has, and the common test already holds the same bound at a million.
class KeyspaceGrowthTest {
    @Test
    fun `growth to sixteen million keys moves a bounded number of buckets per operation`() {
        val keys = 16_000_000
        val keyspace = Keyspace(seed = 11)
        var maxMoved = 0
        repeat(keys) { i ->
            keyspace.put("key:$i".encodeToByteArray(), i)
            maxMoved = maxOf(maxMoved, keyspace.bucketsMovedLastStep)
        }
        assertEquals(keys, keyspace.size)
        assertTrue(maxMoved <= Keyspace.REHASH_BUCKETS_PER_STEP, "an operation moved $maxMoved buckets")
        repeat(keys) { i -> assertEquals(i, keyspace.get("key:$i".encodeToByteArray())!!.value, "key $i") }
    }
}
