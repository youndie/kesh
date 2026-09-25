package io.github.youndie.kesh.server.config

import io.github.youndie.kesh.resp.Reply
import io.github.youndie.kesh.store.Db

/**
 * `CONFIG GET`/`SET` for what kesh can configure at runtime, and `INFO memory`. Only `maxmemory` for
 * now (B-11); the policy and samples arrive with eviction (B-12), the rest of `INFO` with B-15.
 * Errors are `config.c`'s, `redis/redis@7.2.5!/src/config.c` — `configSetCommand`.
 */
class MemoryConfig(
    private val db: Db,
) {
    /**
     * `configGetCommand` for exact names: a map of those kesh knows, as RESP2 pairs — each under the
     * name **as the client first wrote it** (`MaxMemory` stays `MaxMemory`), and once: the map of
     * matches ignores case, so a later `MAXMEMORY` adds nothing.
     */
    fun get(names: List<String>): Reply {
        val out = ArrayList<Reply>()
        val seen = HashSet<String>()
        for (name in names) {
            if (name.lowercase() != MAXMEMORY || !seen.add(name.lowercase())) continue
            out += Reply.Bulk(name.encodeToByteArray())
            out += Reply.Bulk(db.maxMemory.toString().encodeToByteArray())
        }
        return Reply.Multi(out)
    }

    /** `configSetCommand`: pairs, every name known and unrepeated, every value parsed, then applied. */
    fun set(args: List<String>): Reply {
        if (args.size % 2 != 0) return Reply.Error("ERR syntax error")
        val pairs = args.chunked(2)
        pairs.firstOrNull { it[0].lowercase() != MAXMEMORY }?.let {
            return Reply.Error("ERR Unknown option or number of arguments for CONFIG SET - '${it[0]}'")
        }
        if (pairs.size > 1) return failed(pairs[1][0], "duplicate parameter")
        val value = memtoull(pairs[0][1]) ?: return failed(MAXMEMORY, "argument must be a memory value")
        db.maxMemory = value
        return Reply.OK
    }

    /** `INFO memory`, as far as kesh has it: `used_memory`, `maxmemory` and the policy. */
    fun info(): String =
        buildString {
            append("# Memory\r\n")
            append("used_memory:${db.usedMemory}\r\n")
            append("used_memory_human:${bytesToHuman(db.usedMemory)}\r\n")
            append("maxmemory:${db.maxMemory}\r\n")
            append("maxmemory_human:${bytesToHuman(db.maxMemory)}\r\n")
            append("maxmemory_policy:noeviction\r\n")
        }

    private fun failed(
        name: String,
        reason: String,
    ): Reply = Reply.Error("ERR CONFIG SET failed (possibly related to argument '$name') - $reason")

    companion object {
        const val MAXMEMORY = "maxmemory"

        /**
         * `memtoull` (`redis/redis@7.2.5!/src/util.c`): digits and a unit — none or `b`, `k`/`kb`,
         * `m`/`mb`, `g`/`gb`, the short ones decimal and the `b` ones binary, any case. A sign is
         * refused. As in Redis, no digits at all read as 0.
         */
        fun memtoull(text: String): Long? {
            if (text.startsWith("-")) return null
            val digits = text.takeWhile { it in '0'..'9' }
            val multiplier =
                when (text.substring(digits.length).lowercase()) {
                    "", "b" -> 1L
                    "k" -> 1000L
                    "kb" -> 1024L
                    "m" -> 1000L * 1000
                    "mb" -> 1024L * 1024
                    "g" -> 1000L * 1000 * 1000
                    "gb" -> 1024L * 1024 * 1024
                    else -> return null
                }
            val number = if (digits.isEmpty()) 0L else digits.toLongOrNull() ?: return null
            return number * multiplier
        }

        /** `bytesToHuman` (`server.c`): bytes below 1 KiB, then `%.2f` with K, M, G, T, P. */
        fun bytesToHuman(n: Long): String {
            if (n < 1024) return "${n}B"
            val units = listOf("K", "M", "G", "T", "P")
            var scale = 1024.0
            for (unit in units) {
                if (n < scale * 1024) return twoDecimals(n / scale) + unit
                scale *= 1024
            }
            return "${n}B"
        }

        private fun twoDecimals(d: Double): String {
            val hundredths = kotlin.math.round(d * 100).toLong()
            return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
        }
    }
}
