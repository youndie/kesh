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

    /**
     * `string2d`, how sorted sets read a score (`getDoubleFromObject`): the whole string a number,
     * nothing before it, not NaN, and not out of range — `strtod`'s `ERANGE` refuses an overflow to
     * infinity and an underflow to zero, where a written `inf` is taken.
     */
    fun parseScore(text: String): Double? {
        if (text.isEmpty() || text[0] in SPACE) return null
        val value = parse(text) ?: return null
        if (value.isInfinite() && !INFINITY.matches(text)) return null
        if (value == 0.0 && text.substringBefore('e').substringBefore('E').any { it in '1'..'9' }) return null
        return value
    }

    /**
     * Bare `strtod`, how `ZRANGE … BYSCORE` and `ZCOUNT` read a bound (`zslParseRange`): leading
     * space skipped, an empty string is 0, and no range check — `1e400` is infinity.
     */
    fun parseRangeBound(text: String): Double? {
        if (text.isEmpty()) return 0.0
        val trimmed = text.trimStart { it in SPACE }
        if (trimmed.isEmpty()) return null
        return parse(trimmed)
    }

    private const val SPACE = " \t\n\u000B\u000C\r"

    /**
     * `d2string`, how Redis 7.2 prints a score: `inf`, `-inf`, `-0`; an integral value within half of
     * `LLONG_MAX` as an integer; anything else as `fpconv_dtoa` lays out the shortest digits — plain
     * up to 21 digits, fixed-point down to 1e-7, scientific beyond, `1e+21`, `1.5e-07` without the
     * padding. Redis finds those digits with grisu2, which on rare values is not the shortest; kesh's
     * are always the shortest, so on those values the two print different, equally exact, strings.
     */
    fun formatScore(value: Double): String {
        if (value.isNaN()) return "nan"
        if (value.isInfinite()) return if (value < 0) "-inf" else "inf"
        if (value == 0.0) return if (1.0 / value < 0) "-0" else "0"
        val half = (Long.MAX_VALUE / 2).toDouble()
        if (value >= -half && value <= half) {
            val l = value.toLong()
            if (l.toDouble() == value) return l.toString()
        }
        val shortest = value.toString()
        val negative = shortest.startsWith("-")
        val body = shortest.removePrefix("-")
        val mantissa = body.substringBefore('E').substringBefore('e')
        val exponent = body.substringAfter('E', body.substringAfter('e', "0")).toInt()
        val point = mantissa.indexOf('.').let { if (it < 0) mantissa.length else it }
        val allDigits = mantissa.replace(".", "")
        val leading = allDigits.length - allDigits.trimStart('0').length
        val digits = allDigits.trim('0')
        // value = 0.digits × 10^(point - leading + exponent); K is the exponent of the last digit.
        val k = point - leading + exponent - digits.length
        return (if (negative) "-" else "") + emitDigits(digits, k, negative)
    }

    /** `fpconv_dtoa`'s `emit_digits`, from `redis/redis@7.2.5!/deps/fpconv/fpconv_dtoa.c`. */
    private fun emitDigits(
        allDigits: String,
        k: Int,
        negative: Boolean,
    ): String {
        var digits = allDigits
        val exp = kotlin.math.abs(k + digits.length - 1)
        if (k >= 0 && exp < digits.length + 7) return digits + "0".repeat(k)
        if (k < 0 && (k > -7 || exp < 4)) {
            val offset = digits.length - kotlin.math.abs(k)
            return if (offset <= 0) {
                "0." + "0".repeat(-offset) + digits
            } else {
                digits.substring(0, offset) + "." + digits.substring(offset)
            }
        }
        digits = digits.take(18 - (if (negative) 1 else 0))
        val out = StringBuilder()
        out.append(digits[0])
        if (digits.length > 1) out.append('.').append(digits, 1, digits.length)
        out.append('e').append(if (k + allDigits.length - 1 < 0) '-' else '+')
        out.append(exp)
        return out.toString()
    }
}
