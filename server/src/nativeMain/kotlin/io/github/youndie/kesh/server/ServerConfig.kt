package io.github.youndie.kesh.server

import io.github.youndie.kesh.resp.RequestLimits
import io.github.youndie.kesh.server.config.MemoryBudgetCheck
import io.github.youndie.kesh.server.config.MemoryConfig
import io.github.youndie.kesh.store.eviction.Eviction
import io.github.youndie.kesh.store.eviction.EvictionPolicy
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Configuration from the environment. `KESH_` in upper case, not the brief's `kesh_`: the lower-case
 * spelling read as a working-name substitution rather than a decision (`services/server.md`).
 *
 * @property password `requirepass`; `null` means no password, and every connection starts
 *   authenticated, as in Redis.
 * @property maxClients `maxclients`; `null` means derived from the descriptor ceiling at startup
 *   (research D-13, `DescriptorCeiling.kt`).
 * @property queryBufferLimit `client-query-buffer-limit`: unparsed bytes a connection may hold before
 *   it is closed. 1 GB, Redis's default.
 */
data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 6379,
    val password: String? = null,
    val maxClients: Int? = null,
    val limits: RequestLimits = RequestLimits(),
    val queryBufferLimit: Long = 1L shl 30,
    /** `maxmemory`, `memtoull`'s syntax (`100mb`); 0 for no limit (B-11). */
    val maxMemory: Long = 0,
    /** `maxmemory-policy` (B-12); `noeviction` by default, as in Redis. */
    val maxMemoryPolicy: EvictionPolicy = EvictionPolicy.NOEVICTION,
    /** `maxmemory-samples` (B-12). */
    val maxMemorySamples: Int = Eviction.DEFAULT_SAMPLES,
    /** Where the snapshot lives (`dir`, `dbfilename`; B-14). */
    val dir: String = ".",
    val dbFilename: String = "dump.kesh",
    /**
     * Whether the collector's mutator assists are on (B-23, research R-7). `KESH_GC_ASSISTS=off` turns
     * them off with a finite `GC.maxHeapBytes`, the one lever the runtime offers.
     */
    val gcAssists: Boolean = true,
    /**
     * The HTTP port for probes and metrics (B-15); `null` for none. The environment's default is
     * 8080 (`KESH_HTTP_PORT`, `off` for none); a configuration built in code has none unless given one,
     * so tests do not fight over a fixed port.
     */
    val httpPort: Int? = null,
    /** `KESH_SAVE_ON_SHUTDOWN`: whether the drain ends with a `SAVE` (B-16). Off, as the brief's "if configured". */
    val saveOnShutdown: Boolean = false,
    /**
     * `KESH_SHUTDOWN_DRAIN_SECONDS`: kore's drain stage — the connections' 5 s, then the save. The chart
     * derives it from the measured `SAVE` time (research R-5).
     */
    val shutdownDrainSeconds: Int = 15,
    /** `KESH_TERMINATION_GRACE_SECONDS`: the pod's grace period, so kore can refuse a plan that does not fit it. */
    val terminationGraceSeconds: Int? = null,
    /**
     * `KESH_RESIDENT_PEAK_RATIO_TENTHS`: resident memory at its peak per `used_memory`, in tenths — what
     * `maxmemory` is held against the container's limit with (B-26). The chart passes the ratio it
     * sizes the limit with, so the two cannot disagree.
     */
    val residentPeakRatioTenths: Int = MemoryBudgetCheck.DEFAULT_PEAK_RATIO_TENTHS,
) {
    override fun toString(): String {
        val grace = terminationGraceSeconds?.let { "${it}s" } ?: "undeclared"
        return "ServerConfig(host=$host, port=$port, password=${if (password == null) "none" else "set"}, " +
            "maxClients=${maxClients ?: "derived"}, limits=$limits, queryBufferLimit=$queryBufferLimit, " +
            "maxMemory=$maxMemory, policy=${maxMemoryPolicy.configName}/$maxMemorySamples, " +
            "snapshot=$dir/$dbFilename, gcAssists=$gcAssists, httpPort=${httpPort ?: "off"}, " +
            "saveOnShutdown=$saveOnShutdown, drain=${shutdownDrainSeconds}s, grace=$grace, " +
            "residentPeak=${residentPeakRatioTenths / 10}.${residentPeakRatioTenths % 10}x)"
    }

    companion object {
        const val DEFAULT_HTTP_PORT = 8080

        fun fromEnvironment(read: (String) -> String? = ::environmentVariable): ServerConfig {
            val defaults = ServerConfig()

            fun onOff(
                name: String,
                default: Boolean,
            ): Boolean =
                when (val raw = read(name)) {
                    null -> default
                    "on" -> true
                    "off" -> false
                    else -> throw IllegalArgumentException("$name is on or off: $raw")
                }

            fun number(
                name: String,
                range: LongRange,
            ): Long? =
                read(name)?.let { raw ->
                    requireNotNull(
                        raw.toLongOrNull()?.takeIf { it in range },
                    ) { "$name is not a number in $range: $raw" }
                }
            return ServerConfig(
                host = read("KESH_BIND") ?: defaults.host,
                port = number("KESH_PORT", 0L..65535L)?.toInt() ?: defaults.port,
                password = read("KESH_PASSWORD")?.takeIf { it.isNotEmpty() },
                maxClients = number("KESH_MAXCLIENTS", 1L..Int.MAX_VALUE.toLong())?.toInt(),
                limits =
                    defaults.limits.copy(
                        protoMaxBulkLen =
                            number("KESH_PROTO_MAX_BULK_LEN", 1L..Int.MAX_VALUE.toLong())
                                ?: defaults.limits.protoMaxBulkLen,
                    ),
                queryBufferLimit =
                    number("KESH_CLIENT_QUERY_BUFFER_LIMIT", 1L..Long.MAX_VALUE) ?: defaults.queryBufferLimit,
                maxMemory =
                    read("KESH_MAXMEMORY")?.let { raw ->
                        requireNotNull(MemoryConfig.memtoull(raw)) { "KESH_MAXMEMORY is not a memory value: $raw" }
                    } ?: defaults.maxMemory,
                maxMemoryPolicy =
                    read("KESH_MAXMEMORY_POLICY")?.let { raw ->
                        requireNotNull(EvictionPolicy.byName(raw)) {
                            "KESH_MAXMEMORY_POLICY is not one of ${EvictionPolicy.entries.joinToString {
                                it.configName
                            }}: $raw"
                        }
                    } ?: defaults.maxMemoryPolicy,
                maxMemorySamples =
                    number("KESH_MAXMEMORY_SAMPLES", 1L..Int.MAX_VALUE.toLong())?.toInt() ?: defaults.maxMemorySamples,
                dir = read("KESH_DIR")?.takeIf { it.isNotEmpty() } ?: defaults.dir,
                dbFilename = read("KESH_DBFILENAME")?.takeIf { it.isNotEmpty() } ?: defaults.dbFilename,
                gcAssists =
                    when (val raw = read("KESH_GC_ASSISTS")) {
                        null, "on" -> true
                        "off" -> false
                        else -> throw IllegalArgumentException("KESH_GC_ASSISTS is on or off: $raw")
                    },
                saveOnShutdown = onOff("KESH_SAVE_ON_SHUTDOWN", defaults.saveOnShutdown),
                shutdownDrainSeconds =
                    number("KESH_SHUTDOWN_DRAIN_SECONDS", 1L..3_600L)?.toInt() ?: defaults.shutdownDrainSeconds,
                terminationGraceSeconds = number("KESH_TERMINATION_GRACE_SECONDS", 1L..86_400L)?.toInt(),
                residentPeakRatioTenths =
                    number("KESH_RESIDENT_PEAK_RATIO_TENTHS", 10L..1_000L)?.toInt() ?: defaults.residentPeakRatioTenths,
                httpPort =
                    when (read("KESH_HTTP_PORT")) {
                        "off" -> null
                        else -> number("KESH_HTTP_PORT", 0L..65_535L)?.toInt() ?: DEFAULT_HTTP_PORT
                    },
            )
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun environmentVariable(name: String): String? = getenv(name)?.toKString()
