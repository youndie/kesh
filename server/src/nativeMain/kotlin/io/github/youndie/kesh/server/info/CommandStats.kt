package io.github.youndie.kesh.server.info

/**
 * How many times each command ran and how long it took, for `/metrics` — a Prometheus histogram per
 * command, from which a scrape derives commands per second and latency. Counted where Redis counts
 * `total_commands_processed` (`call()`): a command that ran, not one refused before it. Store thread
 * only.
 */
class CommandStats {
    class Command {
        /** Commands that finished at or under each of [BOUNDS]; the last slot is `+Inf`. */
        val buckets = LongArray(BOUNDS.size + 1)
        var count = 0L
            private set
        var totalMicros = 0L
            private set

        fun record(micros: Long) {
            count++
            totalMicros += micros
            var i = 0
            while (i < BOUNDS.size && micros > BOUNDS[i]) i++
            buckets[i]++
        }

        fun copy(): Command =
            Command().also {
                buckets.copyInto(it.buckets)
                it.count = count
                it.totalMicros = totalMicros
            }
    }

    private val commands = LinkedHashMap<String, Command>()

    /** `total_commands_processed`. */
    var processed = 0L
        private set

    fun record(
        name: String,
        micros: Long,
    ) {
        processed++
        commands.getOrPut(name) { Command() }.record(micros)
    }

    /** Every command that has run, by name, sorted — a copy, safe to read off the store thread. */
    fun snapshot(): List<Pair<String, Command>> =
        commands.entries.sortedBy { it.key }.map { (name, c) -> name to c.copy() }

    companion object {
        /** Bucket upper bounds in microseconds: 50 µs to 1 s, then `+Inf`. */
        val BOUNDS =
            longArrayOf(50, 100, 250, 500, 1_000, 2_500, 5_000, 10_000, 25_000, 50_000, 100_000, 250_000, 1_000_000)
    }
}
