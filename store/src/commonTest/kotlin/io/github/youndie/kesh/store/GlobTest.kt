package io.github.youndie.kesh.store

import kotlin.test.Test
import kotlin.test.assertEquals

class GlobTest {
    private fun m(
        pattern: String,
        string: String,
    ) = Glob.matches(pattern.encodeToByteArray(), string.encodeToByteArray())

    @Test
    fun `the glob forms Redis documents for KEYS`() {
        listOf(
            Triple("h?llo", "hello", true),
            Triple("h?llo", "hllo", false),
            Triple("h*llo", "hllo", true),
            Triple("h*llo", "heeeello", true),
            Triple("h[ae]llo", "hallo", true),
            Triple("h[ae]llo", "hillo", false),
            Triple("h[^e]llo", "hallo", true),
            Triple("h[^e]llo", "hello", false),
            Triple("h[a-b]llo", "hbllo", true),
            Triple("h[b-a]llo", "hbllo", true),
            Triple("h\\*llo", "h*llo", true),
            Triple("h\\*llo", "hello", false),
            Triple("*", "", false),
            Triple("user:*", "user:1001", true),
            Triple("*:tags", "post:9:tags", true),
            Triple("a*b*c", "axxbyyc", true),
        ).forEach { (pattern, string, expected) -> assertEquals(expected, m(pattern, string), "'$pattern' against '$string'") }
    }
}
