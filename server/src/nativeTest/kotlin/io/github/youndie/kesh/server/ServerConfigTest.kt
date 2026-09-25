package io.github.youndie.kesh.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ServerConfigTest {
    @Test
    fun `defaults are Redis's port on every interface with no password and a derived ceiling`() {
        // The environment adds the HTTP port for probes and metrics; a configuration built in code has none.
        assertEquals(ServerConfig("0.0.0.0", 6379, httpPort = 8080), ServerConfig.fromEnvironment { null })
    }

    @Test
    fun `the HTTP port is set or turned off from the environment`() {
        assertEquals(9090, ServerConfig.fromEnvironment { if (it == "KESH_HTTP_PORT") "9090" else null }.httpPort)
        assertEquals(null, ServerConfig.fromEnvironment { if (it == "KESH_HTTP_PORT") "off" else null }.httpPort)
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment { if (it == "KESH_HTTP_PORT") "http" else null }
        }
    }

    @Test
    fun `the shutdown is configured from the environment`() {
        val env =
            mapOf(
                "KESH_SAVE_ON_SHUTDOWN" to "on",
                "KESH_SHUTDOWN_DRAIN_SECONDS" to "40",
                "KESH_TERMINATION_GRACE_SECONDS" to "60",
            )
        val config = ServerConfig.fromEnvironment(env::get)
        assertEquals(true, config.saveOnShutdown)
        assertEquals(40, config.shutdownDrainSeconds)
        assertEquals(60, config.terminationGraceSeconds)
        val defaults = ServerConfig.fromEnvironment { null }
        assertEquals(false, defaults.saveOnShutdown)
        assertEquals(15, defaults.shutdownDrainSeconds)
        assertEquals(null, defaults.terminationGraceSeconds, "undeclared: kore assumes Kubernetes' 30 s and says so")
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment { if (it == "KESH_SAVE_ON_SHUTDOWN") "yes" else null }
        }
    }

    @Test
    fun `the environment overrides the defaults`() {
        val env =
            mapOf(
                "KESH_PORT" to "7000",
                "KESH_BIND" to "127.0.0.1",
                "KESH_PASSWORD" to "secret",
                "KESH_MAXCLIENTS" to "50",
                "KESH_PROTO_MAX_BULK_LEN" to "1024",
                "KESH_CLIENT_QUERY_BUFFER_LIMIT" to "4096",
            )
        val config = ServerConfig.fromEnvironment(env::get)
        assertEquals(ServerConfig("127.0.0.1", 7000, "secret", 50, config.limits, 4096, httpPort = 8080), config)
        assertEquals(1024, config.limits.protoMaxBulkLen)
    }

    @Test
    fun `an empty password is no password`() {
        assertEquals(null, ServerConfig.fromEnvironment { if (it == "KESH_PASSWORD") "" else null }.password)
    }

    @Test
    fun `a number that is not in range is refused at startup`() {
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment {
                if (it ==
                    "KESH_PORT"
                ) {
                    "70000"
                } else {
                    null
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment {
                if (it ==
                    "KESH_MAXCLIENTS"
                ) {
                    "0"
                } else {
                    null
                }
            }
        }
    }

    @Test
    fun `the password never appears in the printed configuration`() {
        assertFalse("secret" in ServerConfig(password = "secret").toString())
    }
}
