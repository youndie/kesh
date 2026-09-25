package io.github.youndie.kesh.store

import io.github.youndie.kesh.store.keyspace.Entry
import io.github.youndie.kesh.store.keyspace.Keyspace
import io.github.youndie.kesh.store.memory.MemoryModel

/**
 * The one database (brief §3: `SELECT 0` only), with Redis's lazy expiry: a key whose time has passed
 * is deleted the moment anything looks it up, and is never returned (`redis/redis@7.2!/src/db.c` —
 * `expireIfNeeded`, `keyIsExpired`).
 *
 * Every command runs against one instant, [now], fixed when the command starts — Redis's
 * `commandTimeSnapshot` — so a command never sees a key alive in one step and gone in the next.
 * "Expired" is `now > expireAt`, strictly, as in `keyIsExpired`.
 *
 * **Accounting** ([usedMemory], research D-10): a write command runs between [begin] and [settle].
 * Every entry it looks up, sets or creates is remembered with its size before the command; [settle]
 * adds what each is worth afterwards less what it was worth before. A key removed without having
 * been looked up in the command — an expired one — is subtracted at once. Nothing is stored per
 * key for this: the sizes are [MemoryModel]'s, and every value keeps its own running total.
 */
class Db(
    val seed: Int = 0,
) {
    val keyspace = Keyspace(seed)

    /** The command's instant, in milliseconds since the epoch. Set by the dispatcher per command. */
    var now: Long = 0

    /** Entries in the table, expired or not — what `DBSIZE` counts, as Redis's `dictSize` does. */
    val size: Int get() = keyspace.size

    /** The dataset's estimated size in bytes: every key's entry, key and value, and the bucket arrays. */
    val usedMemory: Long get() = entriesBytes + MemoryModel.buckets(keyspace.capacity)

    /** `maxmemory`: 0 for no limit, as in Redis. */
    var maxMemory: Long = 0

    /** Redis's `used_memory > maxmemory`, the condition a `denyoom` command is refused under. */
    val overLimit: Boolean get() = maxMemory > 0 && usedMemory > maxMemory

    private var entriesBytes = 0L
    private var tracking = false
    private val touched = HashMap<Entry, Long>()
    private val removed = HashSet<Entry>()

    /** Starts a write command: from here until [settle], what it touches is accounted. */
    fun begin() {
        tracking = true
    }

    /** Ends a write command: every touched entry's size is brought up to date. */
    fun settle() {
        for ((entry, before) in touched) {
            val after = if (entry in removed) 0L else MemoryModel.entry(entry)
            entriesBytes += after - before
        }
        touched.clear()
        removed.clear()
        tracking = false
    }

    /** The live entry for [key], deleting it first if it has expired. */
    fun lookup(key: ByteArray): Entry? {
        val entry = keyspace.get(key) ?: return null
        if (isExpired(entry)) {
            remove(key)
            return null
        }
        touch(entry)
        return entry
    }

    fun isExpired(entry: Entry): Boolean = entry.expireAt != Entry.NO_EXPIRY && now > entry.expireAt

    /**
     * Sets [key] to [value]. The expiry is cleared unless [keepTtl] — Redis's `setKey` without
     * `SETKEY_KEEPTTL`, which is every `SET` without `KEEPTTL` and every `MSET` and `GETSET`.
     */
    fun set(
        key: ByteArray,
        value: Any,
        keepTtl: Boolean = false,
    ): Entry {
        val existing = lookup(key) ?: return put(key, value)
        val before = if (tracking) 0L else MemoryModel.entry(existing)
        existing.value = value
        if (!keepTtl) existing.expireAt = Entry.NO_EXPIRY
        if (!tracking) entriesBytes += MemoryModel.entry(existing) - before
        return existing
    }

    /**
     * Puts [key] with [value] and no expiry — a new entry, or the value of an existing one replaced.
     * Outside a command ([begin]…[settle]) the change is accounted at once.
     */
    fun put(
        key: ByteArray,
        value: Any,
    ): Entry {
        val existing = keyspace.get(key)
        existing?.let { touch(it) }
        val before = if (!tracking && existing != null) MemoryModel.entry(existing) else 0L
        val entry = keyspace.put(key, value)
        when {
            !tracking -> entriesBytes += MemoryModel.entry(entry) - before
            entry !in touched -> touched[entry] = 0L
        }
        return entry
    }

    /** Removes [key] whatever its expiry — the one way a key leaves the table. `false` if absent. */
    fun remove(key: ByteArray): Boolean {
        val entry = keyspace.remove(key) ?: return false
        if (entry in touched) removed.add(entry) else entriesBytes -= MemoryModel.entry(entry)
        return true
    }

    /** Removes [key] if it is live; `false` if it was absent or had expired (then it is gone too). */
    fun delete(key: ByteArray): Boolean = lookup(key) != null && remove(key)

    fun clear() {
        keyspace.clear()
        entriesBytes = 0
        touched.clear()
        removed.clear()
    }

    /** [usedMemory] summed from scratch: what the running total must equal. For tests. */
    fun recount(): Long {
        var sum = 0L
        keyspace.forEach { sum += MemoryModel.entry(it) }
        return sum + MemoryModel.buckets(keyspace.capacity)
    }

    private fun touch(entry: Entry) {
        if (tracking && entry !in touched) touched[entry] = MemoryModel.entry(entry)
    }
}
