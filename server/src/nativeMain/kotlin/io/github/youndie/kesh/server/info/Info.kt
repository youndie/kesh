package io.github.youndie.kesh.server.info

import io.github.youndie.kesh.server.client.Clients
import io.github.youndie.kesh.server.config.MemoryConfig.Companion.bytesToHuman
import io.github.youndie.kesh.server.persistence.Persistence
import io.github.youndie.kesh.server.pubsub.PubSub
import io.github.youndie.kesh.store.Db
import io.github.youndie.kesh.store.eviction.Eviction
import io.github.youndie.kesh.store.expiry.ActiveExpiry
import kotlin.random.Random

/**
 * `INFO` (`redis/redis@7.2!/src/server.c` — `genRedisInfoString`): the sections `server`, `clients`,
 * `memory`, `persistence`, `stats` and `keyspace`, in Redis's order and with Redis's field names —
 * **only the fields whose meaning is the same in kesh**. A field Redis has and kesh cannot fill
 * truthfully (`rdb_changes_since_last_save`, `keyspace_hits`, the fork and AOF lines) is left out
 * rather than printed as 0; the conformance harness holds every field kesh does print to exist in
 * Redis's report (`[fields]`). Store thread only.
 */
class Info(
    private val db: Db,
    private val clients: Clients,
    private val stats: CommandStats,
    private val eviction: Eviction,
    private val expiry: ActiveExpiry?,
    private val persistence: Persistence?,
    private val pubsub: PubSub,
    /** The RESP port, once bound; 0 before. */
    private val port: () -> Int,
    /** Milliseconds since the epoch. */
    private val clock: () -> Long,
) {
    private val startedAt = clock()

    /** `server.runid`: forty random hex characters, new every start, as Redis makes it. */
    val runId: String = buildString { repeat(40) { append("0123456789abcdef"[Random.nextInt(16)]) } }

    /** `stat_peak_memory`: the most `used_memory` has been after a command. */
    var peakMemory = 0L
        private set

    /** Called after every command, as Redis records the peak in `call()`. */
    fun afterCommand() {
        val used = db.usedMemory
        if (used > peakMemory) peakMemory = used
    }

    /**
     * The report for [sections] as `INFO` received them, lower-cased: none, `default`, `all` or
     * `everything` for every section kesh has; a name kesh does not have adds nothing, as in Redis.
     */
    fun report(sections: List<String>): String {
        fun wanted(section: String) = sections.isEmpty() || sections.any { it == section || it in ALL_SECTIONS }
        val parts = ArrayList<String>()
        if (wanted("server")) parts += server()
        if (wanted("clients")) parts += clients()
        if (wanted("memory")) parts += memory()
        if (wanted("persistence")) parts += persistence()
        if (wanted("stats")) parts += stats()
        if (wanted("keyspace")) parts += keyspace()
        return parts.joinToString("\r\n")
    }

    private fun server(): String {
        val now = clock()
        val uptime = (now - startedAt) / 1000
        return section(
            "Server",
            "redis_version" to REDIS_COMPATIBLE_VERSION,
            "redis_mode" to "standalone",
            "os" to ProcessFacts.os,
            "arch_bits" to "64",
            // ktor-network's selector on Kotlin/Native is `pselect` (research §1.4): Redis names its
            // own `select` backend "select".
            "multiplexing_api" to "select",
            "process_id" to ProcessFacts.pid,
            "run_id" to runId,
            "tcp_port" to port(),
            "server_time_usec" to now * 1000,
            "uptime_in_seconds" to uptime,
            "uptime_in_days" to uptime / (3600 * 24),
            "hz" to ActiveExpiry.HZ,
            "configured_hz" to ActiveExpiry.HZ,
            "lru_clock" to ((now / Db.LRU_CLOCK_RESOLUTION) and Db.LRU_CLOCK_MAX),
            "executable" to ProcessFacts.executable,
            "config_file" to "",
        )
    }

    private fun clients(): String =
        section(
            "Clients",
            "connected_clients" to clients.size,
            "maxclients" to clients.maxClients,
            // No blocking commands in kesh: none is ever blocked, which is what Redis's 0 means.
            "blocked_clients" to 0,
        )

    private fun memory(): String {
        val used = db.usedMemory
        val resident = ProcessFacts.residentBytes()
        val peak = maxOf(peakMemory, used)
        return section(
            "Memory",
            "used_memory" to used,
            "used_memory_human" to bytesToHuman(used),
            "used_memory_rss" to resident,
            "used_memory_rss_human" to bytesToHuman(resident),
            "used_memory_peak" to peak,
            "used_memory_peak_human" to bytesToHuman(peak),
            "maxmemory" to db.maxMemory,
            "maxmemory_human" to bytesToHuman(db.maxMemory),
            "maxmemory_policy" to eviction.policy.configName,
        )
    }

    private fun persistence(): String =
        section(
            "Persistence",
            // The snapshot loads before the listener binds (research D-24): nothing can ask while it does.
            "loading" to 0,
            "rdb_bgsave_in_progress" to 0,
            "rdb_last_save_time" to (persistence?.lastSave ?: (startedAt / 1000)),
            "rdb_saves" to (persistence?.saves ?: 0),
            "rdb_last_load_keys_loaded" to (persistence?.loadedKeys ?: 0),
            "aof_enabled" to 0,
        )

    private fun stats(): String =
        section(
            "Stats",
            "total_connections_received" to clients.registered,
            "total_commands_processed" to stats.processed,
            "rejected_connections" to clients.rejected,
            "expired_keys" to db.expiredKeys,
            "expired_stale_perc" to twoDecimals((expiry?.stalePerc ?: 0.0) * 100),
            "expired_time_cap_reached_count" to (expiry?.timeCapReached ?: 0),
            "expire_cycle_cpu_milliseconds" to (expiry?.timeUsedMicros ?: 0) / 1000,
            "evicted_keys" to eviction.evictedKeys,
            "pubsub_channels" to pubsub.channelCount,
            "pubsub_patterns" to pubsub.patternCount,
        )

    /** `db0:keys=…,expires=…,avg_ttl=…`, only while the database has keys — as Redis prints it. */
    private fun keyspace(): String {
        val lines = StringBuilder("# Keyspace\r\n")
        if (db.size > 0 || db.expires.size > 0) {
            lines.append("db0:keys=${db.size},expires=${db.expires.size},avg_ttl=${expiry?.avgTtl ?: 0}\r\n")
        }
        return lines.toString()
    }

    private fun section(
        title: String,
        vararg fields: Pair<String, Any>,
    ): String =
        buildString {
            append("# ").append(title).append("\r\n")
            for ((name, value) in fields) append(name).append(':').append(value.toString()).append("\r\n")
        }

    companion object {
        /**
         * The version kesh answers as in `HELLO` and `INFO`: the Redis it is held to by the oracle
         * (research D-16), not kesh's own. `HELLO`'s `server` says `kesh`, so nobody mistakes which.
         */
        const val REDIS_COMPATIBLE_VERSION = "7.2.0"

        /** `INFO`'s words for every section. */
        val ALL_SECTIONS = setOf("all", "everything", "default")

        /** `%.2f`, as `INFO` prints a percentage. */
        fun twoDecimals(d: Double): String {
            val hundredths = kotlin.math.round(d * 100).toLong()
            return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
        }
    }
}
