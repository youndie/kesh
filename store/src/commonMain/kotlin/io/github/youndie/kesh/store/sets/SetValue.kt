package io.github.youndie.kesh.store.sets

import io.github.youndie.kesh.store.ByteSlice
import io.github.youndie.kesh.store.keyspace.Keyspace
import io.github.youndie.kesh.store.memory.MemoryModel
import io.github.youndie.kesh.store.packed.Packed
import kotlin.random.Random

/**
 * A set of byte strings. Packed — one `ByteArray` of members in the [Packed] layout — while it has at
 * most [maxPackedEntries] members of at most [maxPackedValue] bytes, Redis 7.2's listpack limits for
 * sets (`set-max-listpack-entries 128`, `set-max-listpack-value 64`); a [Keyspace] table after, for
 * good. Redis also keeps sets of integers as a sorted `intset`; kesh does not, so a set's reply order
 * differs from Redis's and is compared as a multiset — Redis promises no order for sets (research
 * D-22).
 */
class SetValue(
    private val seed: Int,
) {
    private var packed: ByteArray = Packed.EMPTY
    private var packedCount = 0

    /** The table, once the set is no longer packed; `SSCAN` walks it. */
    internal var table: Keyspace? = null
        private set

    val size: Int get() = table?.size ?: packedCount

    /** Running total of the table's entries and members; 0 while packed (research D-10). */
    private var tableBytes = 0L

    /** The set's estimated size: this object, and its packed bytes or its table. */
    val estimatedBytes: Long
        get() =
            SELF +
                (
                    table?.let {
                        tableBytes +
                            MemoryModel.buckets(
                                it.capacity,
                            )
                    } ?: MemoryModel.array(packed.size.toLong())
                )

    /** [estimatedBytes] summed from scratch, for tests. */
    internal fun recountBytes(): Long {
        val t = table ?: return estimatedBytes
        var sum = 0L
        t.forEach { sum += memberBytes(it.key) }
        return SELF + sum + MemoryModel.buckets(t.capacity)
    }

    /** Whether the set is still packed. Invisible to clients; for tests. */
    val isPacked: Boolean get() = table == null

    fun contains(member: ByteArray): Boolean = table?.let { it.get(member) != null } ?: (find(member) >= 0)

    /** Adds [member]; `true` if it was not there. */
    fun add(member: ByteArray): Boolean {
        if (table == null && member.size > maxPackedValue) convert()
        table?.let { t ->
            if (t.get(member) != null) return false
            t.put(member, Unit)
            tableBytes += memberBytes(member)
            return true
        }
        if (find(member) >= 0) return false
        packed = packed + Packed.encode(member)
        packedCount++
        if (packedCount > maxPackedEntries) convert()
        return true
    }

    /** Removes [member]; `true` if it was there. A set never goes back to packed. */
    fun remove(member: ByteArray): Boolean {
        table?.let { t ->
            val gone = t.remove(member) ?: return false
            tableBytes -= memberBytes(gone.key)
            return true
        }
        val at = find(member)
        if (at < 0) return false
        packed = Packed.splice(packed, at, Packed.skip(packed, at))
        packedCount--
        return true
    }

    /** `setTypeMaybeConvert` before adding [incoming] members: more than a packed set holds converts first. */
    fun prepareFor(incoming: Int) {
        if (table == null && incoming > maxPackedEntries) convert()
    }

    fun forEach(action: (ByteArray) -> Unit) {
        table?.let { t ->
            t.forEach { action(it.key) }
            return
        }
        var at = 0
        while (at < packed.size) {
            action(Packed.read(packed, at))
            at = Packed.skip(packed, at)
        }
    }

    /** Every member, in [forEach]'s order, where it lies — no copy (B-25). */
    fun forEachSlice(visitor: ByteSlice) {
        table?.let { t ->
            t.forEach { visitor.accept(it.key, 0, it.key.size) }
            return
        }
        var at = 0
        while (at < packed.size) at = Packed.visit(packed, at, visitor)
    }

    fun members(): List<ByteArray> = ArrayList<ByteArray>(size).also { out -> forEach { out.add(it) } }

    /** One member at random: uniform while packed, [Keyspace.randomEntry] as a table. Not on an empty set. */
    fun random(random: Random): ByteArray {
        check(size > 0) { "a random member of an empty set" }
        table?.let { t -> return t.randomEntry { random.nextInt(it) }!!.key }
        return Packed.read(packed, Packed.skip(packed, 0, random.nextInt(packedCount)))
    }

    private fun convert() {
        val converted = Keyspace(seed)
        tableBytes = 0
        forEach {
            converted.put(it, Unit)
            tableBytes += memberBytes(it)
        }
        table = converted
        packed = Packed.EMPTY
        packedCount = 0
    }

    private fun find(member: ByteArray): Int {
        var at = 0
        while (at < packed.size) {
            if (Packed.matches(packed, at, member)) return at
            at = Packed.skip(packed, at)
        }
        return -1
    }

    companion object {
        /** This object: header, seed, packed array, count, table, running total. */
        private const val SELF = MemoryModel.OBJECT + 5 * MemoryModel.WORD

        /** One member in the table: its entry and its bytes. */
        private fun memberBytes(bytes: ByteArray): Long = MemoryModel.ENTRY + MemoryModel.array(bytes.size.toLong())

        /** `set-max-listpack-entries`, Redis 7.2's default. */
        var maxPackedEntries: Int = 128

        /** `set-max-listpack-value`, Redis 7.2's default. */
        var maxPackedValue: Int = 64
    }
}
