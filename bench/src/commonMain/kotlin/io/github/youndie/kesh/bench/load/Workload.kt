package io.github.youndie.kesh.bench.load

import io.github.youndie.kesh.bench.Rng

/**
 * §5a's reference load as commands: 80 % reads (`GET`, `HGET`, `HGETALL`, `LRANGE`, `SISMEMBER`,
 * `ZREVRANGE`), 20 % writes (`SET` with a TTL, `HSET`, `INCR`, `LPUSH` with its `LTRIM`, `ZINCRBY`),
 * each on a key of its part drawn by [Zipf].
 *
 * **The weights inside each group are this generator's choice**, not the brief's, which gives only
 * the 80/20 split: reads follow the parts' sizes, sessions first; writes likewise. They are the
 * [OPERATIONS] table, and B-17's report prints it. `LPUSH` and its `LTRIM` are one operation of two
 * commands, sent together, so a feed stays bounded as §5a's are.
 */
class Workload(
    private val keys: KeyCatalogue,
    seed: Long,
) {
    enum class Operation(
        val weight: Int,
        val write: Boolean,
    ) {
        GET(40, false),
        HGET(15, false),
        HGETALL(5, false),
        LRANGE(10, false),
        SISMEMBER(5, false),
        ZREVRANGE(5, false),
        SET(8, true),
        HSET(4, true),
        INCR(4, true),
        LPUSH(2, true),
        ZINCRBY(2, true),
    }

    private val sessions = Zipf(keys.sessions.size, seed = seed xor 1)
    private val profiles = Zipf(keys.profiles.size, seed = seed xor 2)
    private val counters = Zipf(keys.counters.size, seed = seed xor 3)
    private val feeds = Zipf(keys.feeds.size, seed = seed xor 4)
    private val tags = Zipf(keys.tags.size, seed = seed xor 5)
    private val boards = Zipf(keys.boards.size, seed = seed xor 6)
    private val total = Operation.entries.sumOf { it.weight }

    /** An operation drawn by weight. */
    fun operation(rng: Rng): Operation {
        var pick = rng.nextInt(0, total)
        for (op in Operation.entries) {
            if (pick < op.weight) return op
            pick -= op.weight
        }
        error("unreachable")
    }

    /** The commands of one [operation], appended to [out] as RESP arrays; how many commands. */
    fun write(
        op: Operation,
        rng: Rng,
        out: Command,
    ): Int =
        when (op) {
            Operation.GET -> {
                out.command("GET", keys.sessions[sessions.next(rng)])
            }

            Operation.HGET -> {
                val i = profiles.next(rng)
                out.command("HGET", keys.profiles[i], keys.profileFields[i])
            }

            Operation.HGETALL -> {
                out.command("HGETALL", keys.profiles[profiles.next(rng)])
            }

            Operation.LRANGE -> {
                out.command("LRANGE", keys.feeds[feeds.next(rng)], ZERO, NINETEEN)
            }

            Operation.SISMEMBER -> {
                val i = tags.next(rng)
                out.command("SISMEMBER", keys.tags[i], keys.tagMembers[i])
            }

            Operation.ZREVRANGE -> {
                out.command("ZREVRANGE", keys.boards[boards.next(rng)], ZERO, NINE, WITHSCORES)
            }

            Operation.SET -> {
                val value = ByteArray(rng.nextInt(150, 401)) { ('a'.code + rng.nextInt(0, 26)).toByte() }
                out.command(
                    "SET",
                    keys.sessions[sessions.next(rng)],
                    value,
                    EX,
                    "${rng.nextInt(3_600, 86_401)}".encodeToByteArray(),
                )
            }

            Operation.HSET -> {
                val i = profiles.next(rng)
                out.command("HSET", keys.profiles[i], keys.profileFields[i], "v${rng.nextLong()}".encodeToByteArray())
            }

            Operation.INCR -> {
                out.command("INCR", keys.counters[counters.next(rng)])
            }

            Operation.LPUSH -> {
                val key = keys.feeds[feeds.next(rng)]
                out.command("LPUSH", key, "${rng.nextInt(1_000, 100_000)}".encodeToByteArray()) +
                    out.command("LTRIM", key, ZERO, "199".encodeToByteArray())
            }

            Operation.ZINCRBY -> {
                val member = "u${rng.nextInt(1, keys.boardMembers + 1)}".encodeToByteArray()
                out.command("ZINCRBY", keys.boards[boards.next(rng)], ONE, member)
            }
        }

    private companion object {
        val ZERO = "0".encodeToByteArray()
        val ONE = "1".encodeToByteArray()
        val NINE = "9".encodeToByteArray()
        val NINETEEN = "19".encodeToByteArray()
        val EX = "EX".encodeToByteArray()
        val WITHSCORES = "WITHSCORES".encodeToByteArray()
    }
}

/** A buffer of RESP commands; [command] returns 1, so a [Workload.write] can sum what it sent. */
class Command {
    var bytes = ByteArray(64 * 1024)
        private set
    var length = 0
        private set

    fun clear() {
        length = 0
    }

    fun command(
        name: String,
        vararg args: ByteArray,
    ): Int {
        text("*${args.size + 1}\r\n$${name.length}\r\n$name\r\n")
        for (a in args) {
            text("$${a.size}\r\n")
            put(a)
            text("\r\n")
        }
        return 1
    }

    private fun text(s: String) = put(s.encodeToByteArray())

    private fun put(b: ByteArray) {
        if (length + b.size > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, length + b.size))
        b.copyInto(bytes, length)
        length += b.size
    }
}
