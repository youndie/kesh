package io.github.youndie.kesh.store

/**
 * The floating-point numbers of `INCRBYFLOAT` (and later `HINCRBYFLOAT`, `ZADD`, `ZINCRBY`).
 *
 * **Known divergence, by the platform rather than by choice**: Redis computes these in C's `long
 * double` — 80-bit extended precision on x86-64 — and prints the result with `%.17Lf`, trimmed
 * (`redis/redis@7.2!/src/util.c` — `ld2string`, `LD_STR_HUMAN`). Kotlin has no such type. kesh
 * computes in `Double` and prints the shortest decimal that reads back as the same `Double`, in
 * fixed-point and trimmed the same way. The two agree wherever the 64-bit result is already the
 * decimal a person would expect (`10.5 + 0.1` → `10.6`) and differ where x86's extra bits round away
 * an error `Double` keeps (`0.1 + 0.2` → Redis `0.3`, kesh `0.30000000000000004`). Redis itself
 * prints differently on platforms where `long double` is `double`.
 */
object RedisFloat {
    private val DECIMAL = Regex("""^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$""")
    private val INFINITY = Regex("""^[+-]?(inf|infinity)$""", RegexOption.IGNORE_CASE)

    /**
     * Redis's `string2ld` as far as a decimal goes: no surrounding space, nothing after the number,
     * `inf` accepted, `nan` refused. Hexadecimal floats, which `strtold` would also take, are not.
     */
    fun parse(text: String): Double? =
        when {
            DECIMAL.matches(text) -> text.toDouble().takeIf { !it.isNaN() }
            INFINITY.matches(text) -> if (text.startsWith("-")) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
            else -> null
        }

    /** `%.17Lf`, trimmed, over the shortest representation of [value]; `-0` prints as `0`. */
    fun format(value: Double): String {
        if (value == 0.0) return "0"
        val shortest = value.toString()
        val negative = shortest.startsWith("-")
        val body = shortest.removePrefix("-")
        val mantissa = body.substringBefore('E').substringBefore('e')
        val exponent = body.substringAfter('E', body.substringAfter('e', "0")).toInt()
        val digits = mantissa.replace(".", "")
        val point = mantissa.indexOf('.').let { if (it < 0) mantissa.length else it } + exponent
        val fixed =
            when {
                point <= 0 -> "0." + "0".repeat(-point) + digits
                point >= digits.length -> digits + "0".repeat(point - digits.length)
                else -> digits.substring(0, point) + "." + digits.substring(point)
            }
        var integer = fixed.substringBefore('.').trimStart('0').ifEmpty { "0" }
        var fraction = fixed.substringAfter('.', "")
        if (fraction.length > 17) {
            // %.17Lf rounds at the seventeenth decimal, half away from zero on these decimal digits.
            val roundUp = fraction[17] >= '5'
            val kept = (integer + fraction.substring(0, 17)).toCharArray()
            if (roundUp) {
                var i = kept.size - 1
                while (i >= 0) {
                    if (kept[i] == '9') {
                        kept[i] = '0'
                        i--
                    } else {
                        kept[i] = kept[i] + 1
                        break
                    }
                }
                val all = (if (i < 0) "1" else "") + kept.concatToString()
                integer = all.substring(0, all.length - 17).trimStart('0').ifEmpty { "0" }
                fraction = all.substring(all.length - 17)
            } else {
                fraction = fraction.substring(0, 17)
            }
        }
        fraction = fraction.trimEnd('0')
        val text = if (fraction.isEmpty()) integer else "$integer.$fraction"
        return if (negative && text != "0") "-$text" else text
    }
}
