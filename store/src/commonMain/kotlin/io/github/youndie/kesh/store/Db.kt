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

    /**
     * The keys that have an expiry, key to entry — Redis's `db->expires`, what active expiry samples
     * (B-13). Kept exact by [setExpire], [remove] and [clear]: every expiry change goes through them.
     */
    val expires = Keyspace(seed xor 0x5f3759df)

    /** `stat_expiredkeys`: keys deleted because their time had passed, lazily or actively. */
    var expiredKeys: Long = 0
        private set

    /** The command's instant, in milliseconds since the epoch. Set by the dispatcher per command. */
    var now: Long = 0

    /** Entries in the table, expired or not — what `DBSIZE` counts, as Redis's `dictSize` does. */
    val size: Int get() = keyspace.size

    /** The dataset's estimated size in bytes: every key's entry, key and value, and the bucket arrays. */
    val usedMemory: Long
        get() =
            entriesBytes + MemoryModel.buckets(keyspace.capacity) +
                expires.size * MemoryModel.ENTRY + MemoryModel.buckets(expires.capacity)

    /** `maxmemory`: 0 for no limit, as in Redis. */
    var maxMemory: Long = 0

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

    /**
     * The live entry for [key], deleting it first if it has expired. A lookup is an access for the
     * LRU policies (B-12) unless [touch] is false — Redis's `LOOKUP_NOTOUCH`, which `EXISTS`, `TYPE`,
     * the `TTL` family and `SCAN`'s `TYPE` filter pass.
     */
    fun lookup(
        key: ByteArray,
        touch: Boolean = true,
    ): Entry? {
        val entry = keyspace.get(key) ?: return null
        if (isExpired(entry)) {
            expire(entry)
            return null
        }
        if (touch) entry.lru = lruClock
        track(entry)
        return entry
    }

    /** Redis's `LRU_CLOCK()`: [now] in seconds, 24 bits, wrapping every 194 days. */
    val lruClock: Int get() = ((now / LRU_CLOCK_RESOLUTION) and LRU_CLOCK_MAX).toInt()

    /** `estimateObjectIdleTime`: milliseconds since [entry] was last accessed, at the clock's resolution. */
    fun idleMillis(entry: Entry): Long {
        val clock = lruClock.toLong()
        val lru = entry.lru.toLong()
        val seconds = if (clock >= lru) clock - lru else clock + (LRU_CLOCK_MAX - lru)
        return seconds * LRU_CLOCK_RESOLUTION
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
        if (!keepTtl) setExpire(existing, Entry.NO_EXPIRY)
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
        existing?.let { track(it) }
        val before = if (!tracking && existing != null) MemoryModel.entry(existing) else 0L
        val entry = keyspace.put(key, value)
        entry.lru = lruClock
        when {
            !tracking -> entriesBytes += MemoryModel.entry(entry) - before
            entry !in touched -> touched[entry] = 0L
        }
        return entry
    }

    /** Removes [key] whatever its expiry — the one way a key leaves the table. `false` if absent. */
    fun remove(key: ByteArray): Boolean {
        val entry = keyspace.remove(key) ?: return false
        if (entry.expireAt != Entry.NO_EXPIRY) expires.remove(key)
        if (entry in touched) removed.add(entry) else entriesBytes -= MemoryModel.entry(entry)
        return true
    }

    /** Removes [key] if it is live; `false` if it was absent or had expired (then it is gone too). */
    fun delete(key: ByteArray): Boolean = lookup(key) != null && remove(key)

    /** Sets [entry]'s expiry — [Entry.NO_EXPIRY] to clear it — keeping [expires] exact. */
    fun setExpire(
        entry: Entry,
        at: Long,
    ) {
        if (at == Entry.NO_EXPIRY) {
            if (entry.expireAt != Entry.NO_EXPIRY) expires.remove(entry.key)
        } else if (entry.expireAt == Entry.NO_EXPIRY) {
            expires.put(entry.key, entry)
        }
        entry.expireAt = at
    }

    /**
     * The table upkeep of Redis's `databasesCron`, for the store's periodic work (B-13): a table under
     * 10 % full starts shrinking (`tryResizeHashTables`), and a resize in progress moves up to
     * [REHASH_BUDGET] buckets (`incrementallyRehash`) so it ends while commands leave it alone. For
     * the keyspace and the expiry index, as Redis for `dict` and `expires`.
     */
    fun resizeAndRehash() {
        for (table in listOf(keyspace, expires)) {
            table.shrinkIfSparse()
            table.rehashFor(REHASH_BUDGET)
        }
    }

    /** Deletes [entry], whose time has passed: `deleteExpiredKeyAndPropagate`, counted. */
    fun expire(entry: Entry) {
        if (remove(entry.key)) expiredKeys++
    }

    fun clear() {
        keyspace.clear()
        expires.clear()
        entriesBytes = 0
        touched.clear()
        removed.clear()
    }

    /** [usedMemory] summed from scratch: what the running total must equal. For tests. */
    fun recount(): Long {
        var sum = 0L
        keyspace.forEach { sum += MemoryModel.entry(it) }
        return sum + MemoryModel.buckets(keyspace.capacity) +
            expires.size * MemoryModel.ENTRY + MemoryModel.buckets(expires.capacity)
    }

    companion object {
        /**
         * Buckets a periodic call moves at most — Redis's millisecond of `dictRehashMilliseconds`,
         * counted in buckets (about a millisecond of moves here). It has to finish a shrink quickly:
         * while one is in progress the expiry index counts both tables' buckets, looks sparse, and
         * active expiry waits (`num*100/slots < 1`, as in Redis). At 1 000 a shrink of a 131 072-bucket
         * index took 14 cycles, and the feature's 2 s scenario failed on linuxX64.
         */
        const val REHASH_BUDGET = 16_384

        /** `LRU_CLOCK_RESOLUTION`: the LRU clock counts seconds. */
        const val LRU_CLOCK_RESOLUTION = 1_000L

        /** `LRU_CLOCK_MAX`: 24 bits, as `robj->lru` has. */
        const val LRU_CLOCK_MAX = (1L shl 24) - 1
    }

    private fun track(entry: Entry) {
        if (tracking && entry !in touched) touched[entry] = MemoryModel.entry(entry)
    }
}
