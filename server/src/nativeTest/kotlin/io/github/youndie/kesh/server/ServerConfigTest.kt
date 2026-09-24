package io.github.youndie.kesh.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ServerConfigTest {
    @Test
    fun `defaults are Redis's port on every interface with no password and a derived ceiling`() {
        assertEquals(ServerConfig("0.0.0.0", 6379), ServerConfig.fromEnvironment { null })
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
        assertEquals(ServerConfig("127.0.0.1", 7000, "secret", 50, config.limits, 4096), config)
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
