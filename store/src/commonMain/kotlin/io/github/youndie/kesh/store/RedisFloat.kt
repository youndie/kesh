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

    /**
     * `ld2string(…, LD_STR_AUTO)`: `%.17Lg` of the score widened to `long double` — the form `ZSCAN`
     * gives a skiplist-encoded set's scores (`scanCallback` in `db.c`), unlike every other command.
     * Seventeen significant digits of the double's **exact** value, correctly rounded, so `0.1` prints
     * `0.10000000000000001`; `%g`'s layout, trailing zeros dropped. The widening is exact, so the
     * double's own expansion is the one printed.
     */
    fun formatLongDoubleAuto(value: Double): String {
        if (value.isNaN()) return "nan"
        if (value.isInfinite()) return if (value < 0) "-inf" else "inf"
        val negative = value < 0 || (value == 0.0 && 1.0 / value < 0)
        val sign = if (negative) "-" else ""
        if (value == 0.0) return sign + "0"
        val (digits, exponent) = exactDecimal(kotlin.math.abs(value))
        // digits × 10^exponent, digits without leading zeros. Round to 17 significant, half to even.
        var kept = digits
        var exp10 = exponent
        if (digits.length > 17) {
            val head = digits.substring(0, 17)
            val tail = digits.substring(17)
            val roundUp =
                when {
                    tail[0] > '5' -> true
                    tail[0] < '5' -> false
                    tail.substring(1).any { it != '0' } -> true
                    else -> (head.last() - '0') % 2 == 1
                }
            kept = if (roundUp) incrementDecimal(head) else head
            exp10 += digits.length - 17
            if (kept.length > 17) {
                kept = kept.substring(0, 17)
                exp10 += 1
            }
        }
        // The decimal exponent of the first significant digit, as %e would print it.
        val x = kept.length - 1 + exp10
        val body =
            if (x < -4 || x >= 17) {
                val mantissa = (kept.substring(0, 1) + "." + kept.substring(1)).trimEnd('0').trimEnd('.')
                val e =
                    kotlin.math
                        .abs(x)
                        .toString()
                        .padStart(2, '0')
                mantissa + "e" + (if (x < 0) "-" else "+") + e
            } else if (x >= kept.length - 1) {
                kept + "0".repeat(x - (kept.length - 1))
            } else if (x >= 0) {
                (kept.substring(0, x + 1) + "." + kept.substring(x + 1)).trimEnd('0').trimEnd('.')
            } else {
                ("0." + "0".repeat(-x - 1) + kept).trimEnd('0')
            }
        return sign + body
    }

    /** The exact value of a positive finite [value] as decimal digits and a power of ten. */
    private fun exactDecimal(value: Double): Pair<String, Int> {
        val bits = value.toRawBits()
        val biased = ((bits ushr 52) and 0x7ff).toInt()
        val fraction = bits and 0xfffffffffffffL
        val mantissa = if (biased == 0) fraction else fraction or (1L shl 52)
        val e2 = (if (biased == 0) 1 else biased) - 1075
        // Limbs of 10^9, least significant first.
        var limbs = IntArray(0)
        var m = mantissa
        while (m > 0) {
            limbs += (m % 1_000_000_000L).toInt()
            m /= 1_000_000_000L
        }
        if (e2 >= 0) {
            repeat(e2) { limbs = multiply(limbs, 2) }
            return trimZeros(toDecimal(limbs), 0)
        }
        // m / 2^k = m × 5^k / 10^k.
        repeat(-e2) { limbs = multiply(limbs, 5) }
        return trimZeros(toDecimal(limbs), e2)
    }

    private fun multiply(
        limbs: IntArray,
        factor: Int,
    ): IntArray {
        var carry = 0L
        val out = IntArray(limbs.size + 1)
        for (i in limbs.indices) {
            val product = limbs[i].toLong() * factor + carry
            out[i] = (product % 1_000_000_000L).toInt()
            carry = product / 1_000_000_000L
        }
        out[limbs.size] = carry.toInt()
        return if (carry == 0L) out.copyOf(limbs.size) else out
    }

    private fun toDecimal(limbs: IntArray): String {
        val out = StringBuilder()
        out.append(limbs.last())
        for (i in limbs.size - 2 downTo 0) out.append(limbs[i].toString().padStart(9, '0'))
        return out.toString()
    }

    /** Moves trailing zeros of [digits] into the exponent. */
    private fun trimZeros(
        digits: String,
        exponent: Int,
    ): Pair<String, Int> {
        val trimmed = digits.trimEnd('0')
        return trimmed to exponent + (digits.length - trimmed.length)
    }

    private fun incrementDecimal(digits: String): String {
        val chars = digits.toCharArray()
        var i = chars.size - 1
        while (i >= 0) {
            if (chars[i] == '9') {
                chars[i] = '0'
                i--
            } else {
                chars[i] = chars[i] + 1
                return chars.concatToString()
            }
        }
        return "1" + chars.concatToString()
    }
}
