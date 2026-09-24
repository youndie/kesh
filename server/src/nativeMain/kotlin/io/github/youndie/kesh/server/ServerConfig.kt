package io.github.youndie.kesh.server

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Configuration from the environment. `KESH_` in upper case, not the brief's `kesh_`: the lower-case
 * spelling read as a working-name substitution rather than a decision (see `services/server.md`).
 */
data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 6379,
) {
    companion object {
        fun fromEnvironment(read: (String) -> String? = ::environmentVariable): ServerConfig {
            val defaults = ServerConfig()
            val port =
                read("KESH_PORT")?.let {
                    requireNotNull(it.toIntOrNull()?.takeIf { p -> p in 0..65535 }) { "KESH_PORT is not a port: $it" }
                } ?: defaults.port
            return ServerConfig(host = read("KESH_BIND") ?: defaults.host, port = port)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun environmentVariable(name: String): String? = getenv(name)?.toKString()
