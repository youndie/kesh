package io.github.youndie.kesh.server

import io.github.youndie.kesh.resp.RequestLimits
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
) {
    override fun toString(): String =
        "ServerConfig(host=$host, port=$port, password=${if (password == null) "none" else "set"}, " +
            "maxClients=${maxClients ?: "derived"}, limits=$limits, queryBufferLimit=$queryBufferLimit)"

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
            )
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun environmentVariable(name: String): String? = getenv(name)?.toKString()
