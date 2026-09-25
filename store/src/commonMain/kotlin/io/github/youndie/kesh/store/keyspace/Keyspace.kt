package io.github.youndie.kesh.store.keyspace

/**
 * One key of the keyspace: its value and its absolute expiry time in milliseconds, or [NO_EXPIRY].
 * The expiry lives on the entry rather than in a second table; active expiry (B-13) adds its own index.
 */
class Entry internal constructor(
    val key: ByteArray,
    internal val hash: Int,
    var value: Any,
    var expireAt: Long,
    internal var next: Entry?,
) {
    /**
     * When the key was last created or looked up, in seconds of the LRU clock — Redis's 24-bit
     * `robj->lru`, what the LRU policies evict by (B-12). Fits the entry's padding: no cost.
     */
    internal var lru: Int = 0

    companion object {
        const val NO_EXPIRY: Long = -1
    }
}

/**
 * kesh's own hash table (research D-12), shaped like Redis's `dict`: power-of-two tables of bucket
 * chains, and **incremental rehashing** — while the table grows, the old and the new one live side by
 * side, and every operation moves at most [REHASH_BUCKETS_PER_STEP] buckets (visiting at most ten
 * times as many empty ones) from the old to the new. No single command pays for the whole table, which
 * the standard library's `HashMap` makes the one that crosses its threshold do (research §1.3).
 *
 * The same shape is what `SCAN`'s guarantee needs (B-10): a power-of-two table and a reverse-binary
 * cursor stay valid across a resize.
 *
 * Keys hash by content — a `ByteArray` compares by identity — with a per-process [seed], so that keys
 * chosen from outside cannot be made to collide on purpose.
 *
 * Grows when the entries reach the capacity (load factor 1, as Redis without a fork in progress);
 * shrinks when the store's periodic work finds it under 10 % full ([shrinkIfSparse], B-13) — both
 * incrementally. Store thread only.
 */
class Keyspace(
    private val seed: Int = 0,
) {
    private var tables: Array<Array<Entry?>?> = arrayOf(arrayOfNulls(INITIAL_CAPACITY), null)
    private var rehashIndex = -1
    private val sizes = IntArray(2)

    /** Buckets moved by the last operation that did rehash work; what the growth test bounds. */
    var bucketsMovedLastStep: Int = 0
        private set

    val size: Int get() = sizes[0] + sizes[1]

    val isRehashing: Boolean get() = rehashIndex >= 0

    /** Buckets across both tables — what the table itself costs beyond its entries. */
    val capacity: Int get() = (tables[0]?.size ?: 0) + (tables[1]?.size ?: 0)

    fun get(key: ByteArray): Entry? {
        if (size == 0) return null
        rehashStep()
        val hash = hash(key)
        for (t in 0..1) {
            val table = tables[t] ?: break
            var entry = table[hash and (table.size - 1)]
            while (entry != null) {
                if (entry.hash == hash && entry.key.contentEquals(key)) return entry
                entry = entry.next
            }
            if (!isRehashing) break
        }
        return null
    }

    /** Adds [key] or replaces its value; a new entry has no expiry. Returns the entry. */
    fun put(
        key: ByteArray,
        value: Any,
    ): Entry {
        get(key)?.let {
            it.value = value
            return it
        }
        expandIfNeeded()
        val hash = hash(key)
        val t = if (isRehashing) 1 else 0
        val table = tables[t]!!
        val bucket = hash and (table.size - 1)
        val entry = Entry(key, hash, value, Entry.NO_EXPIRY, table[bucket])
        table[bucket] = entry
        sizes[t]++
        return entry
    }

    fun remove(key: ByteArray): Entry? {
        if (size == 0) return null
        rehashStep()
        val hash = hash(key)
        for (t in 0..1) {
            val table = tables[t] ?: break
            val bucket = hash and (table.size - 1)
            var previous: Entry? = null
            var entry = table[bucket]
            while (entry != null) {
                if (entry.hash == hash && entry.key.contentEquals(key)) {
                    if (previous == null) table[bucket] = entry.next else previous.next = entry.next
                    entry.next = null
                    sizes[t]--
                    return entry
                }
                previous = entry
                entry = entry.next
            }
            if (!isRehashing) break
        }
        return null
    }

    fun clear() {
        tables = arrayOf(arrayOfNulls(INITIAL_CAPACITY), null)
        rehashIndex = -1
        sizes.fill(0)
    }

    /** Every entry, in table order. The table must not change during the walk. */
    fun forEach(action: (Entry) -> Unit) {
        for (table in tables) {
            if (table == null) continue
            for (head in table) {
                var entry = head
                while (entry != null) {
                    val next = entry.next
                    action(entry)
                    entry = next
                }
            }
        }
    }

    /**
     * One step of Redis's `dictScan` (`redis/redis@7.2!/src/dict.c` — `dictScanDefrag`): emits the
     * entries of the bucket at [cursor] — and, during a resize, of every bucket of the larger table
     * that bucket expands into — and returns the next cursor, 0 when the table has been covered.
     *
     * The cursor counts in **reversed** bits, so it walks the high bits of a bucket index first: a
     * power-of-two table that doubles in between keeps every bucket not yet visited at a cursor not
     * yet reached. That is `SCAN`'s guarantee — every entry present for the whole iteration is
     * emitted at least once — and why the table has power-of-two sizes. The table must not change
     * during [emit]; no rehash step runs here.
     */
    fun scan(
        cursor: Long,
        emit: (Entry) -> Unit,
    ): Long {
        if (size == 0) return 0
        var v = cursor
        if (!isRehashing) {
            val table = tables[0]!!
            val m0 = (table.size - 1).toLong()
            emitBucket(table, (v and m0).toInt(), emit)
            v = v or m0.inv()
            v = reverse(reverse(v) + 1)
        } else {
            var small = tables[0]!!
            var large = tables[1]!!
            if (small.size > large.size) small = large.also { large = small }
            val m0 = (small.size - 1).toLong()
            val m1 = (large.size - 1).toLong()
            emitBucket(small, (v and m0).toInt(), emit)
            do {
                emitBucket(large, (v and m1).toInt(), emit)
                v = v or m1.inv()
                v = reverse(reverse(v) + 1)
            } while (v and (m0 xor m1) != 0L)
        }
        return v
    }

    private fun emitBucket(
        table: Array<Entry?>,
        bucket: Int,
        emit: (Entry) -> Unit,
    ) {
        var entry = table[bucket]
        while (entry != null) {
            val next = entry.next
            emit(entry)
            entry = next
        }
    }

    /**
     * An entry chosen as Redis's `dictGetFairRandomKey` does in spirit: a random non-empty bucket, then
     * a random entry of its chain. [random] returns a value in `[0, bound)`.
     */
    fun randomEntry(random: (Int) -> Int): Entry? {
        if (size == 0) return null
        rehashStep()
        while (true) {
            val t = if (isRehashing && random(size) < sizes[1]) 1 else 0
            val table = tables[t] ?: continue
            var head: Entry? = null
            for (attempt in 0 until table.size * 2) {
                head = table[random(table.size)]
                if (head != null) break
            }
            if (head == null) continue
            var length = 0
            var e = head
            while (e != null) {
                length++
                e = e.next
            }
            var pick = random(length)
            e = head
            while (pick-- > 0) e = e!!.next
            return e
        }
    }

    /**
     * Up to [count] entries from a random stretch of the table, for the eviction pool (B-12) —
     * Redis's `dictGetSomeKeys` (`redis/redis@7.2!/src/dict.c`): a random bucket, then the buckets
     * after it, every entry of each chain, jumping elsewhere after a run of empty ones, at most
     * `count * 10` steps. Not a uniform sample and not meant to be; entries may repeat across calls.
     * [random] returns a value in `[0, bound)`.
     */
    fun sample(
        count: Int,
        random: (Int) -> Int,
    ): List<Entry> {
        val wanted = minOf(count, size)
        if (wanted == 0) return emptyList()
        if (isRehashing) rehashStep()
        val tableCount = if (isRehashing) 2 else 1
        var mask = tables[0]!!.size - 1
        if (tableCount == 2) mask = maxOf(mask, tables[1]!!.size - 1)
        val out = ArrayList<Entry>(wanted)
        var stored = 0
        var i = random(mask + 1)
        var emptyRun = 0
        var steps = wanted.toLong() * 10
        while (stored < wanted && steps-- > 0) {
            for (t in 0 until tableCount) {
                // Below the rehash index the old table is already empty (`dictGetSomeKeys`' invariant).
                if (tableCount == 2 && t == 0 && i < rehashIndex) {
                    if (i >= tables[1]!!.size) i = rehashIndex else continue
                }
                val table = tables[t]!!
                if (i >= table.size) continue
                var entry = table[i]
                if (entry == null) {
                    emptyRun++
                    if (emptyRun >= 5 && emptyRun > wanted) {
                        i = random(mask + 1)
                        emptyRun = 0
                    }
                } else {
                    emptyRun = 0
                    while (entry != null) {
                        // Reservoir sampling past the count, so the end of a long chain can be drawn too.
                        if (stored < wanted) {
                            out.add(entry)
                        } else {
                            val r = random(stored + 1)
                            if (r < wanted) out[r] = entry
                        }
                        entry = entry.next
                        stored++
                    }
                    if (stored >= wanted) return out
                }
            }
            i = (i + 1) and mask
        }
        return out
    }

    private fun expandIfNeeded() {
        if (isRehashing) return
        val table = tables[0]!!
        if (sizes[0] < table.size) return
        tables[1] = arrayOfNulls(table.size * 2)
        rehashIndex = 0
    }

    /** One command's share of a rehash in progress: [REHASH_BUCKETS_PER_STEP] buckets. */
    private fun rehashStep() {
        bucketsMovedLastStep = rehash(REHASH_BUCKETS_PER_STEP)
    }

    /**
     * Redis's `tryResizeHashTables` for one table (`htNeedsResize`): a table more than
     * [INITIAL_CAPACITY] buckets and under 10 % full starts shrinking to the smallest power of two
     * that holds its entries, rehashed incrementally like a growth. `true` if it started. Called by
     * the store's periodic work (B-13), never by a command.
     */
    fun shrinkIfSparse(): Boolean {
        if (isRehashing) return false
        val capacity = tables[0]!!.size
        if (capacity <= INITIAL_CAPACITY || size * 100L / capacity >= MIN_FILL_PERCENT) return false
        var target = INITIAL_CAPACITY
        while (target < size) target *= 2
        if (target >= capacity) return false
        tables[1] = arrayOfNulls(target)
        rehashIndex = 0
        return true
    }

    /**
     * Moves up to [buckets] non-empty buckets of a rehash in progress — Redis's `incrementallyRehash`,
     * which the periodic work runs so that a resize finishes while no command touches the table.
     */
    fun rehashFor(buckets: Int): Int = rehash(buckets)

    /** Moves up to [limit] non-empty buckets from the old table to the new one, visiting at most ten times as many empty ones. */
    private fun rehash(limit: Int): Int {
        if (!isRehashing) return 0
        val old = tables[0]!!
        val new = tables[1]!!
        var emptyVisits = limit * 10
        var moved = 0
        while (moved < limit && rehashIndex < old.size) {
            var entry = old[rehashIndex]
            if (entry == null) {
                rehashIndex++
                if (--emptyVisits == 0) break
                continue
            }
            while (entry != null) {
                val next = entry.next
                val bucket = entry.hash and (new.size - 1)
                entry.next = new[bucket]
                new[bucket] = entry
                sizes[0]--
                sizes[1]++
                entry = next
            }
            old[rehashIndex] = null
            rehashIndex++
            moved++
        }
        if (rehashIndex >= old.size) {
            tables[0] = new
            tables[1] = null
            sizes[0] = sizes[1]
            sizes[1] = 0
            rehashIndex = -1
        }
        return moved
    }

    /** FNV-1a over the key, mixed with the seed and finished like MurmurHash3's fmix32. */
    private fun hash(key: ByteArray): Int {
        var h = -0x7ee3623b xor seed
        for (b in key) h = (h xor (b.toInt() and 0xFF)) * 0x01000193
        h = h xor (h ushr 16)
        h *= -0x7a143595
        h = h xor (h ushr 13)
        h *= -0x3d4d51cb
        return h xor (h ushr 16)
    }

    companion object {
        const val INITIAL_CAPACITY = 4

        /** `HASHTABLE_MIN_FILL`: below this per cent full, a table is shrunk. */
        const val MIN_FILL_PERCENT = 10

        /** Redis's `_dictRehashStep` moves one bucket per operation; so does this. */
        const val REHASH_BUCKETS_PER_STEP = 1

        /** The bits of [v] in reverse order — `rev` in `dict.c`. */
        fun reverse(v: Long): Long {
            var x = v
            var r = 0L
            repeat(64) {
                r = (r shl 1) or (x and 1L)
                x = x ushr 1
            }
            return r
        }
    }
}
