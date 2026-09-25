package io.github.youndie.kesh.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `d2string` and `fpconv_dtoa`'s layout, and the two ways Redis reads a score. */
class ScoreFormatTest {
    @Test
    fun `integers print as integers within half of LLONG_MAX`() {
        assertEquals("3", RedisFloat.formatScore(3.0))
        assertEquals("-42", RedisFloat.formatScore(-42.0))
        assertEquals("0", RedisFloat.formatScore(0.0))
        assertEquals("-0", RedisFloat.formatScore(-0.0))
        assertEquals("5e+18", RedisFloat.formatScore(5e18))
    }

    @Test
    fun `the shortest digits in fpconv's layout`() {
        assertEquals("1.5", RedisFloat.formatScore(1.5))
        assertEquals("0.1", RedisFloat.formatScore(0.1))
        assertEquals("0.30000000000000004", RedisFloat.formatScore(0.1 + 0.2))
        assertEquals("0.000001", RedisFloat.formatScore(1e-6))
        assertEquals("0.000025", RedisFloat.formatScore(2.5e-5))
        assertEquals("1e-7", RedisFloat.formatScore(1e-7))
        assertEquals("1.5e-7", RedisFloat.formatScore(1.5e-7))
        assertEquals("1e+20", RedisFloat.formatScore(1e20))
        assertEquals("123456789012345680000", RedisFloat.formatScore(1.2345678901234568e20))
        assertEquals("-1.5e+300", RedisFloat.formatScore(-1.5e300))
        assertEquals("inf", RedisFloat.formatScore(Double.POSITIVE_INFINITY))
        assertEquals("-inf", RedisFloat.formatScore(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `a score is read as string2d reads it`() {
        assertEquals(1.5, RedisFloat.parseScore("1.5"))
        assertEquals(Double.POSITIVE_INFINITY, RedisFloat.parseScore("+inf"))
        assertNull(RedisFloat.parseScore(" 1"))
        assertNull(RedisFloat.parseScore(""))
        assertNull(RedisFloat.parseScore("1e400"), "overflow is ERANGE")
        assertNull(RedisFloat.parseScore("1e-400"), "underflow to zero is ERANGE")
        assertNull(RedisFloat.parseScore("nan"))
    }

    @Test
    fun `a range bound is read as bare strtod reads it`() {
        assertEquals(0.0, RedisFloat.parseRangeBound(""))
        assertEquals(1.0, RedisFloat.parseRangeBound("  1"))
        assertEquals(Double.POSITIVE_INFINITY, RedisFloat.parseRangeBound("1e400"))
        assertNull(RedisFloat.parseRangeBound("1 "))
        assertNull(RedisFloat.parseRangeBound("  "))
        assertNull(RedisFloat.parseRangeBound("nan"))
    }
}
