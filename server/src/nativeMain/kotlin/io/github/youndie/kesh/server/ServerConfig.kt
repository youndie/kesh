package io.github.youndie.kesh.server

import io.github.youndie.kesh.resp.RequestLimits
import io.github.youndie.kesh.server.config.MemoryConfig
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
    /** Where the snapshot lives (`dir`, `dbfilename`; B-14). */
    val dir: String = ".",
    val dbFilename: String = "dump.kesh",
    /**
     * Whether the collector's mutator assists are on (B-23, research R-7). `KESH_GC_ASSISTS=off` turns
     * them off with a finite `GC.maxHeapBytes`, the one lever the runtime offers.
     */
    val gcAssists: Boolean = true,
) {
    override fun toString(): String =
        "ServerConfig(host=$host, port=$port, password=${if (password == null) "none" else "set"}, " +
            "maxClients=${maxClients ?: "derived"}, limits=$limits, queryBufferLimit=$queryBufferLimit, " +
            "maxMemory=$maxMemory, snapshot=$dir/$dbFilename, gcAssists=$gcAssists)"

    companion object {
        fun fromEnvironment(read: (String) -> String? = ::environmentVariable): ServerConfig {
            val defaults = ServerConfig()

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
                dir = read("KESH_DIR")?.takeIf { it.isNotEmpty() } ?: defaults.dir,
                dbFilename = read("KESH_DBFILENAME")?.takeIf { it.isNotEmpty() } ?: defaults.dbFilename,
                gcAssists =
                    when (val raw = read("KESH_GC_ASSISTS")) {
                        null, "on" -> true
                        "off" -> false
                        else -> throw IllegalArgumentException("KESH_GC_ASSISTS is on or off: $raw")
                    },
            )
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun environmentVariable(name: String): String? = getenv(name)?.toKString()
