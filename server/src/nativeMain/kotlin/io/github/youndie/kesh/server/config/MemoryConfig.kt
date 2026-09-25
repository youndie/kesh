package io.github.youndie.kesh.server.config

import io.github.youndie.kesh.resp.Reply
import io.github.youndie.kesh.resp.parseRedisLong
import io.github.youndie.kesh.store.Db
import io.github.youndie.kesh.store.eviction.Eviction
import io.github.youndie.kesh.store.eviction.EvictionPolicy

/**
 * `CONFIG GET`/`SET` for what kesh can configure at runtime, and `INFO memory`: `maxmemory` (B-11),
 * `maxmemory-policy` and `maxmemory-samples` (B-12); the rest of `INFO` with B-15. Errors are
 * `config.c`'s, `redis/redis@7.2!/src/config.c` — `configSetCommand`, `enumConfigSet`,
 * `numericParseString`, `numericBoundaryCheck`.
 */
class MemoryConfig(
    private val db: Db,
    private val eviction: Eviction,
) {
    /** One parameter: its canonical name, its value as `CONFIG GET` prints it, and its parser. */
    private class Param(
        val name: String,
        val get: () -> String,
        /** The value to apply, or the reason it is refused. */
        val parse: (String) -> Result<() -> Unit>,
    )

    private val params =
        listOf(
            Param(MAXMEMORY, { db.maxMemory.toString() }) { raw ->
                val value = memtoull(raw) ?: return@Param refused("argument must be a memory value")
                Result.success {
                    db.maxMemory = value
                    // `updateMaxmemory`: evicting starts at once and goes on between commands.
                    if (value != 0L) eviction.start()
                }
            },
            Param(MAXMEMORY_POLICY, { eviction.policy.configName }) { raw ->
                val policy =
                    EvictionPolicy.byName(raw)
                        ?: return@Param if (raw.lowercase() in LFU_POLICIES) {
                            refused("kesh does not implement the LFU policies")
                        } else {
                            refused("argument(s) must be one of the following: ${REDIS_POLICIES.joinToString(", ")}")
                        }
                Result.success { eviction.policy = policy }
            },
            Param(MAXMEMORY_SAMPLES, { eviction.samples.toString() }) { raw ->
                val value =
                    parseRedisLong(raw.encodeToByteArray())
                        ?: return@Param refused("argument couldn't be parsed into an integer")
                if (value !in 1L..Int.MAX_VALUE) {
                    return@Param refused("argument must be between 1 and ${Int.MAX_VALUE} inclusive")
                }
                Result.success { eviction.samples = value.toInt() }
            },
        )

    private fun find(name: String): Param? = params.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * `configGetCommand` for exact names: a map of those kesh knows, as RESP2 pairs — each under the
     * name **as the client first wrote it** (`MaxMemory` stays `MaxMemory`), and once: the map of
     * matches ignores case, so a later `MAXMEMORY` adds nothing. Redis answers several names in its
     * dictionary's order, which is not the request's; kesh answers in the request's.
     */
    fun get(names: List<String>): Reply {
        val out = ArrayList<Reply>()
        val seen = HashSet<String>()
        for (name in names) {
            val param = find(name) ?: continue
            if (!seen.add(param.name)) continue
            out += Reply.Bulk(name.encodeToByteArray())
            out += Reply.Bulk(param.get().encodeToByteArray())
        }
        return Reply.Multi(out)
    }

    /**
     * `configSetCommand`: pairs; the first unknown or repeated name fails the whole command; then every
     * value is parsed, the first refusal failing it under the parameter's own name; then all apply.
     */
    fun set(args: List<String>): Reply {
        if (args.size % 2 != 0) return Reply.Error("ERR syntax error")
        val pairs = args.chunked(2)
        val found = ArrayList<Param>()
        for ((name, _) in pairs) {
            val param =
                find(name) ?: return Reply.Error("ERR Unknown option or number of arguments for CONFIG SET - '$name'")
            if (param in found) return failed(name, "duplicate parameter")
            found += param
        }
        val apply =
            found.mapIndexed { i, param ->
                param.parse(pairs[i][1]).getOrElse { return failed(param.name, it.message!!) }
            }
        apply.forEach { it() }
        return Reply.OK
    }

    private fun failed(
        name: String,
        reason: String,
    ): Reply = Reply.Error("ERR CONFIG SET failed (possibly related to argument '$name') - $reason")

    private fun refused(reason: String): Result<() -> Unit> = Result.failure(IllegalArgumentException(reason))

    companion object {
        const val MAXMEMORY = "maxmemory"
        const val MAXMEMORY_POLICY = "maxmemory-policy"
        const val MAXMEMORY_SAMPLES = "maxmemory-samples"

        /** `maxmemory_policy_enum`, in its order: what Redis's refusal lists, LFU included. */
        val REDIS_POLICIES =
            listOf(
                "volatile-lru",
                "volatile-lfu",
                "volatile-random",
                "volatile-ttl",
                "allkeys-lru",
                "allkeys-lfu",
                "allkeys-random",
                "noeviction",
            )

        /** Policies Redis has and kesh does not (feature-memory-limit, out of scope). */
        val LFU_POLICIES = setOf("volatile-lfu", "allkeys-lfu")

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
