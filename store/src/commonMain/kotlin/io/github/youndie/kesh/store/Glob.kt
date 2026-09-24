package io.github.youndie.kesh.store

/**
 * Redis's glob matching (`redis/redis@7.2!/src/util.c` — `stringmatchlen`), for `KEYS` and, later,
 * `SCAN … MATCH`: `*`, `?`, `[abc]`, `[^a]`, `[a-z]` and `\` escapes, over bytes.
 *
 * Ported with the C version's reads past the end made explicit: where it reads the terminating NUL
 * of an sds string, this reads 0.
 */
object Glob {
    fun matches(
        pattern: ByteArray,
        string: ByteArray,
    ): Boolean {
        val skipLongerMatches = BooleanArray(1)
        return match(pattern, 0, pattern.size, string, 0, string.size, skipLongerMatches, 0)
    }

    private fun match(
        pattern: ByteArray,
        pStart: Int,
        pLen: Int,
        string: ByteArray,
        sStart: Int,
        sLen: Int,
        skipLongerMatches: BooleanArray,
        nesting: Int,
    ): Boolean {
        if (nesting > 1000) return false
        var p = pStart
        var patternLen = pLen
        var s = sStart
        var stringLen = sLen
        fun pat(i: Int): Int = if (i < pattern.size) pattern[i].toInt() and 0xFF else 0
        fun str(i: Int): Int = if (i < string.size) string[i].toInt() and 0xFF else 0

        while (patternLen > 0 && stringLen > 0) {
            when (pat(p)) {
                '*'.code -> {
                    while (patternLen > 0 && pat(p + 1) == '*'.code) {
                        p++
                        patternLen--
                    }
                    if (patternLen == 1) return true
                    while (stringLen > 0) {
                        if (match(pattern, p + 1, patternLen - 1, string, s, stringLen, skipLongerMatches, nesting + 1)) {
                            return true
                        }
                        if (skipLongerMatches[0]) return false
                        s++
                        stringLen--
                    }
                    skipLongerMatches[0] = true
                    return false
                }
                '?'.code -> {
                    s++
                    stringLen--
                }
                '['.code -> {
                    p++
                    patternLen--
                    val not = pat(p) == '^'.code
                    if (not) {
                        p++
                        patternLen--
                    }
                    var matched = false
                    while (true) {
                        if (pat(p) == '\\'.code && patternLen >= 2) {
                            p++
                            patternLen--
                            if (pat(p) == str(s)) matched = true
                        } else if (pat(p) == ']'.code) {
                            break
                        } else if (patternLen == 0) {
                            p--
                            patternLen++
                            break
                        } else if (patternLen >= 3 && pat(p + 1) == '-'.code) {
                            var start = pat(p)
                            var end = pat(p + 2)
                            if (start > end) {
                                val t = start
                                start = end
                                end = t
                            }
                            p += 2
                            patternLen -= 2
                            if (str(s) in start..end) matched = true
                        } else if (pat(p) == str(s)) {
                            matched = true
                        }
                        p++
                        patternLen--
                    }
                    if (not) matched = !matched
                    if (!matched) return false
                    s++
                    stringLen--
                }
                else -> {
                    if (pat(p) == '\\'.code && patternLen >= 2) {
                        p++
                        patternLen--
                    }
                    if (pat(p) != str(s)) return false
                    s++
                    stringLen--
                }
            }
            p++
            patternLen--
            if (stringLen == 0) {
                while (pat(p) == '*'.code && patternLen > 0) {
                    p++
                    patternLen--
                }
                break
            }
        }
        return patternLen == 0 && stringLen == 0
    }
}
