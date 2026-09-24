package io.github.youndie.kesh.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RedisFloatTest {
    @Test
    fun `numbers are printed fixed-point and trimmed as Redis prints them`() {
        assertEquals("10.6", RedisFloat.format(10.5 + 0.1))
        assertEquals("5200", RedisFloat.format(5.0e3 + 2.0e2))
        assertEquals("3", RedisFloat.format(3.0))
        assertEquals("0", RedisFloat.format(-0.0))
        assertEquals("-1.5", RedisFloat.format(-1.5))
        assertEquals("100000000000000000000", RedisFloat.format(1e20))
        assertEquals("0.0001", RedisFloat.format(1e-4))
        assertEquals("0.00012345678901235", RedisFloat.format(1.2345678901234567e-4))
        assertEquals("0", RedisFloat.format(1e-20))
    }

    @Test
    fun `the known divergence from x86 long double is what it says`() {
        // Redis on x86-64 answers 0.3 here; see RedisFloat's KDoc.
        assertEquals("0.30000000000000004", RedisFloat.format(0.1 + 0.2))
    }

    @Test
    fun `parsing accepts what string2ld accepts and refuses the rest`() {
        assertEquals(10.5, RedisFloat.parse("10.50"))
        assertEquals(5000.0, RedisFloat.parse("5.0e3"))
        assertEquals(0.5, RedisFloat.parse(".5"))
        assertEquals(Double.POSITIVE_INFINITY, RedisFloat.parse("inf"))
        listOf(
            "",
            " 1",
            "1 ",
            "nan",
            "abc",
            "1.5x",
            "1f",
        ).forEach { assertNull(RedisFloat.parse(it), "accepted '$it'") }
    }
}
