package io.github.youndie.kesh.store.eviction

import io.github.youndie.kesh.store.Db
import io.github.youndie.kesh.store.keyspace.Entry
import io.github.youndie.kesh.store.keyspace.Keyspace
import kotlin.random.Random
import kotlin.time.TimeSource

/** `maxmemory-policy`: what to do when `used_memory` is over `maxmemory`. */
enum class EvictionPolicy(
    /** The name `CONFIG` takes and answers. */
    val configName: String,
    /** Every key is a candidate, not only those with an expiry. */
    val allKeys: Boolean,
) {
    VOLATILE_LRU("volatile-lru", false),
    VOLATILE_RANDOM("volatile-random", false),
    VOLATILE_TTL("volatile-ttl", false),
    ALLKEYS_LRU("allkeys-lru", true),
    ALLKEYS_RANDOM("allkeys-random", true),
    NOEVICTION("noeviction", false),
    ;

    val random: Boolean get() = this == VOLATILE_RANDOM || this == ALLKEYS_RANDOM

    companion object {
        fun byName(name: String): EvictionPolicy? =
            entries.firstOrNull { it.configName.equals(name, ignoreCase = true) }
    }
}

/**
 * Redis's eviction (`redis/redis@7.2!/src/evict.c` — `performEvictions`, `evictionPoolPopulate`),
 * run before every command while `maxmemory` is set, against [Db.usedMemory] (research D-10): nothing
 * is freed synchronously on this runtime (D-15), so the policy works on kesh's accounting, and
 * resident memory follows at the next sweep.
 *
 * The LRU and TTL policies keep Redis's **pool** of the [POOL_SIZE] best candidates seen so far; each
 * round samples [samples] keys ([Keyspace.sample]) into it and evicts the best one still in the
 * table. LRU ranks by [Db.idleMillis], `volatile-ttl` by the nearest expiry. The random policies take
 * [Keyspace.randomEntry]. Evicting stops when the freed accounting covers the excess — or, every 16
 * keys, when [TIME_LIMIT_MICROS] has passed; then [running] is set and the server keeps evicting
 * between commands ([proceed]), Redis's `evictionTimeProc`.
 */
class Eviction(
    /** Microseconds from a monotonic clock, for the time limit. */
    private val clock: () -> Long = monotonicMicros(),
    private val random: Random = Random.Default,
) {
    var policy: EvictionPolicy = EvictionPolicy.NOEVICTION

    /** `maxmemory-samples`. */
    var samples: Int = DEFAULT_SAMPLES

    /** `stat_evictedkeys`, for `INFO stats`. */
    var evictedKeys: Long = 0
        private set

    /** Redis's `isEvictionProcRunning`: an eviction left unfinished at the time limit goes on. */
    var running: Boolean = false
        private set

    private val poolKeys = arrayOfNulls<ByteArray>(POOL_SIZE)
    private val poolIdle = LongArray(POOL_SIZE)

    /** `performEvictions`: frees what it can of the excess over `maxmemory` and says how it went. */
    fun perform(db: Db): Result {
        if (db.maxMemory == 0L) return Result.OK
        val used = db.usedMemory
        if (used <= db.maxMemory) return Result.OK
        if (policy == EvictionPolicy.NOEVICTION) return Result.FAIL

        val toFree = used - db.maxMemory
        val start = clock()
        var freed = 0L
        var keysFreed = 0
        while (freed < toFree) {
            val victim = (if (policy.random) randomVictim(db) else pooledVictim(db)) ?: return Result.FAIL
            val before = db.usedMemory
            db.remove(victim.key)
            freed += before - db.usedMemory
            evictedKeys++
            keysFreed++
            if (keysFreed % 16 == 0 && clock() - start > TIME_LIMIT_MICROS) {
                running = true
                break
            }
        }
        return if (running) Result.RUNNING else Result.OK
    }

    /** `startEvictionTimeProc`: `CONFIG SET maxmemory` asks for eviction to run between commands. */
    fun start() {
        running = true
    }

    /** `evictionTimeProc`: one more round between commands; `false` once there is nothing left to do. */
    fun proceed(db: Db): Boolean {
        if (perform(db) == Result.RUNNING) return true
        running = false
        return false
    }

    private fun randomVictim(db: Db): Entry? {
        val table = if (policy.allKeys) db.keyspace else db.expires
        val entry = table.randomEntry { random.nextInt(it) } ?: return null
        return if (policy.allKeys) entry else entry.value as Entry
    }

    private fun pooledVictim(db: Db): Entry? {
        val table = if (policy.allKeys) db.keyspace else db.expires
        while (true) {
            if (table.size == 0) return null
            populate(db, table)
            for (k in POOL_SIZE - 1 downTo 0) {
                val key = poolKeys[k] ?: continue
                poolKeys[k] = null
                poolIdle[k] = 0
                // A key gone since it was pooled is a ghost; the next best is tried.
                val found = table.get(key) ?: continue
                return if (policy.allKeys) found else found.value as Entry
            }
        }
    }

    /** `evictionPoolPopulate`: the sample goes into the pool, kept in ascending order of idleness. */
    private fun populate(
        db: Db,
        table: Keyspace,
    ) {
        for (sampled in table.sample(samples) { random.nextInt(it) }) {
            val entry = if (policy.allKeys) sampled else sampled.value as Entry
            val idle =
                if (policy == EvictionPolicy.VOLATILE_TTL) {
                    Long.MAX_VALUE - entry.expireAt
                } else {
                    db.idleMillis(entry)
                }
            var k = 0
            while (k < POOL_SIZE && poolKeys[k] != null && poolIdle[k] < idle) k++
            if (k == 0 && poolKeys[POOL_SIZE - 1] != null) continue
            if (k < POOL_SIZE && poolKeys[k] == null) {
                // An empty slot: nothing to move.
            } else if (poolKeys[POOL_SIZE - 1] == null) {
                poolKeys.copyInto(poolKeys, k + 1, k, POOL_SIZE - 1)
                poolIdle.copyInto(poolIdle, k + 1, k, POOL_SIZE - 1)
            } else {
                k--
                poolKeys.copyInto(poolKeys, 0, 1, k + 1)
                poolIdle.copyInto(poolIdle, 0, 1, k + 1)
            }
            poolKeys[k] = entry.key
            poolIdle[k] = idle
        }
    }

    enum class Result {
        /** Under the limit, or back under it. */
        OK,

        /** Still over, and eviction goes on between commands. */
        RUNNING,

        /** Over the limit with nothing the policy may evict: `denyoom` commands are refused. */
        FAIL,
    }

    companion object {
        /** `EVPOOL_SIZE`. */
        const val POOL_SIZE = 16

        /** `maxmemory-samples`' default. */
        const val DEFAULT_SAMPLES = 5

        /** `evictionTimeLimitUs` at the default `maxmemory-eviction-tenacity` 10: 500 µs. */
        const val TIME_LIMIT_MICROS = 500L

        private fun monotonicMicros(): () -> Long {
            val start = TimeSource.Monotonic.markNow()
            return { start.elapsedNow().inWholeMicroseconds }
        }
    }
}
