package io.github.youndie.kesh.bench.heap

/**
 * The keyspace as research D-12 describes it — kesh's own open-addressing table, keys compared by
 * content — reduced to what the heap probe needs: put, get, and a key by slot for the churn to pick.
 *
 * Two arrays of references, one entry per key: the table itself is a handful of objects however many
 * keys it holds, so what the collector marks is the keys and values, which is what B-19 measures.
 * No incremental rehash — the probe sizes the table once — and no deletion.
 */
class ProbeKeyspace(
    expectedKeys: Int,
) {
    private val capacity = Integer.highestPowerOfTwoAtLeast(expectedKeys * 2)
    private val keys = arrayOfNulls<ByteArray>(capacity)
    private val values = arrayOfNulls<Any>(capacity)
    private val mask = capacity - 1

    var size = 0
        private set

    /** The slots that hold a key, in insertion order, so a random key is one array read away. */
    private val occupied = IntArray(expectedKeys)

    fun put(
        key: ByteArray,
        value: Any,
    ) {
        var slot = hash(key) and mask
        while (true) {
            val existing = keys[slot]
            if (existing == null) {
                keys[slot] = key
                values[slot] = value
                occupied[size++] = slot
                return
            }
            if (existing.contentEquals(key)) {
                values[slot] = value
                return
            }
            slot = (slot + 1) and mask
        }
    }

    fun slotOf(ordinal: Int): Int = occupied[ordinal]

    fun keyAt(slot: Int): ByteArray = keys[slot]!!

    fun valueAt(slot: Int): Any = values[slot]!!

    fun replaceAt(
        slot: Int,
        value: Any,
    ) {
        values[slot] = value
    }

    private fun hash(key: ByteArray): Int {
        var h = -0x7ee3623b
        for (b in key) h = (h xor b.toInt()) * 0x01000193
        return h xor (h ushr 16)
    }
}

private object Integer {
    fun highestPowerOfTwoAtLeast(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }
}
