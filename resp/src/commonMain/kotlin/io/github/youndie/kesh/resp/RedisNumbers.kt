package io.github.youndie.kesh.resp

/**
 * Redis's `string2ll` (`redis/redis@7.2!/src/util.c`): a signed 64-bit integer written exactly one
 * way. No sign on positives, no leading zeros, no spaces, nothing after the digits. `"007"`, `"+7"`
 * and `"7 "` are not integers to Redis, so they are not to kesh either — a lenient parser would
 * accept requests the oracle refuses.
 */
fun parseRedisLong(
    bytes: ByteArray,
    from: Int = 0,
    until: Int = bytes.size,
): Long? {
    val length = until - from
    if (length <= 0 || length >= LONG_STR_SIZE) return null
    if (length == 1 && bytes[from] == '0'.code.toByte()) return 0
    var i = from
    val negative = bytes[i] == '-'.code.toByte()
    if (negative) {
        i++
        if (i == until) return null
    }
    if (bytes[i] !in '1'.code.toByte()..'9'.code.toByte()) return null
    var value = 0UL
    while (i < until) {
        val digit = bytes[i] - '0'.code.toByte()
        if (digit !in 0..9) return null
        if (value > ULong.MAX_VALUE / 10UL) return null
        value *= 10UL
        if (value > ULong.MAX_VALUE - digit.toULong()) return null
        value += digit.toULong()
        i++
    }
    return if (negative) {
        if (value > Long.MAX_VALUE.toULong() + 1UL) null else (0UL - value).toLong()
    } else {
        if (value > Long.MAX_VALUE.toULong()) null else value.toLong()
    }
}

fun parseRedisLong(text: String): Long? = parseRedisLong(text.encodeToByteArray())

/** `LONG_STR_SIZE` in Redis: 21 bytes, the longest a 64-bit integer can be written plus one. */
private const val LONG_STR_SIZE = 21
