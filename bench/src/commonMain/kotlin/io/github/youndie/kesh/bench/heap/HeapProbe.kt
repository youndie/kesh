package io.github.youndie.kesh.bench.heap

import io.github.youndie.kesh.bench.HashEntry
import io.github.youndie.kesh.bench.ListEntry
import io.github.youndie.kesh.bench.Part
import io.github.youndie.kesh.bench.ReferenceDataset
import io.github.youndie.kesh.bench.Rng
import io.github.youndie.kesh.bench.SetEntry
import io.github.youndie.kesh.bench.SortedSetEntry
import io.github.youndie.kesh.bench.StringEntry

/**
 * The reference dataset in the managed heap, and a write churn over it (B-19, research D-17).
 *
 * [load] builds the whole dataset in one [ProbeKeyspace] in the given [encoding]. [churn] then applies
 * the write half of the reference load (research appendix A: `SET` with TTL, `HSET`, `INCR`,
 * `LPUSH`+`LTRIM`, `ZINCRBY`), one command in five each, on keys drawn uniformly — uniform rather
 * than Zipf because it touches more of the old heap and so leaves the collector more to do: the
 * conservative side of the question. One thread, as the store has one (research D-14).
 */
class HeapProbe(
    private val dataset: ReferenceDataset,
    private val encoding: Encoding,
    seed: Long,
) {
    private val keyspace = ProbeKeyspace(Part.entries.sumOf { dataset.keyCount(it) })
    private val rng = Rng(seed xor 0x5eed)

    /** Ordinal ranges of each part in the keyspace's insertion order. */
    private val ranges = HashMap<Part, IntRange>()

    fun load() {
        var ordinal = 0
        var partStart = 0
        var part: Part? = null
        for (entry in dataset.entries()) {
            if (entry.part != part) {
                part?.let { ranges[it] = partStart until ordinal }
                part = entry.part
                partStart = ordinal
            }
            val value: Any =
                when (entry) {
                    is StringEntry -> entry.value
                    is HashEntry -> encoding.hash(entry)
                    is ListEntry -> encoding.list(entry)
                    is SetEntry -> encoding.set(entry)
                    is SortedSetEntry -> encoding.sortedSet(entry)
                }
            keyspace.put(entry.key, value)
            ordinal++
        }
        part?.let { ranges[it] = partStart until ordinal }
    }

    val keys: Int get() = keyspace.size

    /** Runs [operations] write commands; returns how many ran. */
    fun churn(operations: Long): Long {
        for (i in 0 until operations) {
            when (rng.nextInt(0, 5)) {
                0 -> {
                    replaceString(Part.SESSIONS) { text(rng.nextInt(150, 401)) }
                }

                1 -> {
                    update(Part.PROFILES) { encoding.churnHash(it, rng, text(rng.nextInt(10, 40))) }
                }

                2 -> {
                    replaceString(Part.COUNTERS) { rng.nextInt(1, 61).toString().encodeToByteArray() }
                }

                3 -> {
                    update(
                        Part.FEEDS,
                    ) { encoding.churnList(it, rng.nextInt(1_000, 100_000).toString().encodeToByteArray()) }
                }

                else -> {
                    update(Part.LEADERBOARDS) { encoding.churnSortedSet(it, rng) }
                }
            }
        }
        return operations
    }

    private inline fun replaceString(
        part: Part,
        value: () -> ByteArray,
    ) {
        val slot = keyspace.slotOf(pick(part))
        keyspace.replaceAt(slot, value())
    }

    private inline fun update(
        part: Part,
        change: (Any) -> Any,
    ) {
        val slot = keyspace.slotOf(pick(part))
        keyspace.replaceAt(slot, change(keyspace.valueAt(slot)))
    }

    private fun pick(part: Part): Int {
        val range = ranges.getValue(part)
        return rng.nextInt(range.first, range.last + 1)
    }

    private fun text(length: Int): ByteArray = ByteArray(length) { ('a'.code + rng.nextInt(0, 26)).toByte() }
}
