package io.github.youndie.kesh.resp

/**
 * Redis's `sdssplitargs` (`redis/redis@7.2!/src/sds.c`), the tokenizer of inline commands — what a
 * person types into `telnet` or `nc`.
 *
 * Tokens are separated by whitespace. `"double quotes"` understand `\n \r \t \b \a`, `\xHH` and a
 * backslash before any other character; `'single quotes'` understand only `\'`. A closing quote must
 * be followed by whitespace or the end, and a quote may open in the middle of a token (`ab"cd"` is
 * `abcd`). A NUL byte ends the line, because Redis reads it as a C string.
 *
 * @return the arguments, or `null` for unbalanced quotes — which Redis answers with
 *   `Protocol error: unbalanced quotes in request` and a closed connection.
 */
fun splitInlineArguments(line: ByteArray): List<ByteArray>? {
    val args = ArrayList<ByteArray>()
    var p = 0

    fun at(i: Int): Int = if (i < line.size) line[i].toInt() and 0xFF else 0

    while (true) {
        while (at(p) != 0 && isSpace(at(p))) p++
        if (at(p) == 0) return args

        val current = ByteBuilder()
        var inDouble = false
        var inSingle = false
        var done = false
        while (!done) {
            val c = at(p)
            when {
                inDouble -> {
                    when {
                        c == '\\'.code && at(p + 1) == 'x'.code && isHex(at(p + 2)) && isHex(at(p + 3)) -> {
                            current.add(hexValue(at(p + 2)) * 16 + hexValue(at(p + 3)))
                            p += 3
                        }

                        c == '\\'.code && at(p + 1) != 0 -> {
                            p++
                            current.add(
                                when (at(p)) {
                                    'n'.code -> '\n'.code
                                    'r'.code -> '\r'.code
                                    't'.code -> '\t'.code
                                    'b'.code -> 8
                                    'a'.code -> 7
                                    else -> at(p)
                                },
                            )
                        }

                        c == '"'.code -> {
                            if (at(p + 1) != 0 && !isSpace(at(p + 1))) return null
                            done = true
                        }

                        c == 0 -> {
                            return null
                        }

                        else -> {
                            current.add(c)
                        }
                    }
                }

                inSingle -> {
                    when {
                        c == '\\'.code && at(p + 1) == '\''.code -> {
                            p++
                            current.add('\''.code)
                        }

                        c == '\''.code -> {
                            if (at(p + 1) != 0 && !isSpace(at(p + 1))) return null
                            done = true
                        }

                        c == 0 -> {
                            return null
                        }

                        else -> {
                            current.add(c)
                        }
                    }
                }

                else -> {
                    when (c) {
                        ' '.code, '\n'.code, '\r'.code, '\t'.code, 0 -> done = true
                        '"'.code -> inDouble = true
                        '\''.code -> inSingle = true
                        else -> current.add(c)
                    }
                }
            }
            if (at(p) != 0) p++
        }
        args += current.toByteArray()
    }
}

/** C's `isspace` in the "C" locale. */
private fun isSpace(c: Int): Boolean = c == ' '.code || c in 9..13

private fun isHex(c: Int): Boolean = c in '0'.code..'9'.code || c in 'a'.code..'f'.code || c in 'A'.code..'F'.code

private fun hexValue(c: Int): Int =
    when (c) {
        in '0'.code..'9'.code -> c - '0'.code
        in 'a'.code..'f'.code -> c - 'a'.code + 10
        else -> c - 'A'.code + 10
    }

private class ByteBuilder {
    private var bytes = ByteArray(16)
    private var size = 0

    fun add(value: Int) {
        if (size == bytes.size) bytes = bytes.copyOf(size * 2)
        bytes[size++] = value.toByte()
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)
}
