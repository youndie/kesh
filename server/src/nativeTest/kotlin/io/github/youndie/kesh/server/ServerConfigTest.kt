package io.github.youndie.kesh.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerConfigTest {
    @Test
    fun `defaults are Redis's port on every interface`() {
        assertEquals(ServerConfig("0.0.0.0", 6379), ServerConfig.fromEnvironment { null })
    }

    @Test
    fun `KESH_PORT and KESH_BIND override the defaults`() {
        val env = mapOf("KESH_PORT" to "7000", "KESH_BIND" to "127.0.0.1")
        assertEquals(ServerConfig("127.0.0.1", 7000), ServerConfig.fromEnvironment(env::get))
    }

    @Test
    fun `a port that is not a port is refused at startup`() {
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
    }
}
