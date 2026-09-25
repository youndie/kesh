package io.github.youndie.kesh.store.expiry

import io.github.youndie.kesh.store.Db
import io.github.youndie.kesh.store.keyspace.Entry

/**
 * Redis's slow active expiry cycle, `activeExpireCycle(ACTIVE_EXPIRE_CYCLE_SLOW)` in
 * `redis/redis@7.2!/src/expire.c`, with the default effort (research D-11, corrected): each call is
 * one cycle, meant to run [HZ] times a second on the store thread. A cycle walks [Db.expires] with
 * the `dictScan` cursor, 20 keys a loop, deleting those whose time has passed, and loops again while
 * more than 10 % of a loop's sample was expired — within 25 % of the cycle's period, checked every
 * 16 loops. A sparse table (under 1 % full) is left alone, as Redis leaves it.
 *
 * Redis also runs a *fast* cycle before sleeping when the stale share is high; kesh does not — there
 * is no "before sleep" in a coroutine server, and the slow cycle alone meets the feature's scenario.
 */
class ActiveExpiry(
    /** Microseconds from a monotonic clock, for the time limit. */
    private val clock: () -> Long,
) {
    private var cursor = 0L

    /** Cycles that stopped at the time limit — Redis's `expired_time_cap_reached_count`. */
    var timeCapReached: Long = 0
        private set

    /** Microseconds spent in cycles — `expire_cycle_cpu_milliseconds`, in microseconds. */
    var timeUsedMicros: Long = 0
        private set

    /** The running share of expired keys among those sampled — `expired_stale_perc`, 0 to 1. */
    var stalePerc: Double = 0.0
        private set

    /** One slow cycle over [db], at [Db.now]; returns how many keys it deleted. */
    fun cycle(db: Db): Int {
        val start = clock()
        var iteration = 0
        var totalSampled = 0L
        var totalExpired = 0L
        do {
            iteration++
            val num = db.expires.size
            if (num == 0) break
            val slots = db.expires.capacity
            if (slots > INITIAL_SLOTS && num * 100L / slots < 1) break
            val wanted = minOf(num, KEYS_PER_LOOP)
            val maxBuckets = wanted * 20L
            var checkedBuckets = 0L
            var sampled = 0
            val due = ArrayList<Entry>()
            while (sampled < wanted && checkedBuckets < maxBuckets) {
                cursor =
                    db.expires.scan(cursor) {
                        sampled++
                        val entry = it.value as Entry
                        if (db.isExpired(entry)) due.add(entry)
                    }
                checkedBuckets++
            }
            // Deleted after the scan step, not inside it: the table must not change under dictScan.
            due.forEach { db.expire(it) }
            totalSampled += sampled
            totalExpired += due.size
            if (iteration and 0xf == 0 && clock() - start > TIME_LIMIT_MICROS) {
                timeCapReached++
                break
            }
        } while (sampled == 0 || due.size * 100 / sampled > ACCEPTABLE_STALE)
        timeUsedMicros += clock() - start
        val current = if (totalSampled > 0) totalExpired.toDouble() / totalSampled else 0.0
        stalePerc = current * 0.05 + stalePerc * 0.95
        return totalExpired.toInt()
    }

    companion object {
        /** `server.hz`, Redis's default: cycles a second. */
        const val HZ = 10

        /** `ACTIVE_EXPIRE_CYCLE_KEYS_PER_LOOP`. */
        const val KEYS_PER_LOOP = 20

        /** `ACTIVE_EXPIRE_CYCLE_ACCEPTABLE_STALE`, per cent. */
        const val ACCEPTABLE_STALE = 10

        /** `ACTIVE_EXPIRE_CYCLE_SLOW_TIME_PERC` (25 %) of a cycle's period at [HZ]: 25 ms. */
        const val TIME_LIMIT_MICROS = 25L * 1_000_000 / HZ / 100

        /** `DICT_HT_INITIAL_SIZE`: Redis's smallest table, below which sparseness is not judged. */
        private const val INITIAL_SLOTS = 4
    }
}
