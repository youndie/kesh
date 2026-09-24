package io.github.youndie.kesh.resp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RedisNumbersTest {
    @Test
    fun `integers Redis accepts`() {
        assertEquals(0L, parseRedisLong("0"))
        assertEquals(-1L, parseRedisLong("-1"))
        assertEquals(Long.MAX_VALUE, parseRedisLong("9223372036854775807"))
        assertEquals(Long.MIN_VALUE, parseRedisLong("-9223372036854775808"))
    }

    @Test
    fun `integers Redis refuses`() {
        listOf(
            "",
            "-",
            "+1",
            "01",
            "-0",
            "1 ",
            " 1",
            "1a",
            "9223372036854775808",
            "-9223372036854775809",
            "1".repeat(21),
        ).forEach { assertNull(parseRedisLong(it), "accepted '$it'") }
    }
}
