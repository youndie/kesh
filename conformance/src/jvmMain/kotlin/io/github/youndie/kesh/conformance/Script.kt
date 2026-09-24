package io.github.youndie.kesh.conformance

import io.github.youndie.kesh.resp.Reply
import io.github.youndie.kesh.resp.encode
import io.github.youndie.kesh.resp.splitInlineArguments
import java.io.File

/**
 * A conformance script: one command per line, written as `redis-cli` would take it (quotes and
 * escapes as Redis's own `sdssplitargs`), optionally prefixed by what the line needs.
 *
 * ```
 * # requires: password        a script header: run against the password-protected pair
 * PING                        exact comparison of one reply
 * [unordered] SMEMBERS s      a normaliser, named on the line it applies to
 * [shape] CLIENT ID
 * [closes] QUIT               one reply, then both servers must close the connection
 * [raw] *1\r\n$x\r\n          these bytes as they are; everything until the server closes (or goes
 *                             quiet) is the reply, and whether it closed is compared too
 * ```
 *
 * `#` starts a comment line; blank lines are ignored.
 */
class Script(
    val name: String,
    val requiresPassword: Boolean,
    val steps: List<Step>,
) {
    class Step(
        val line: Int,
        val source: String,
        val bytes: ByteArray,
        val kind: Kind,
        val normaliser: Normaliser,
    )

    enum class Kind { COMMAND, CLOSES, RAW }

    companion object {
        fun parse(
            name: String,
            text: String,
        ): Script {
            var requiresPassword = false
            val steps = ArrayList<Step>()
            text.lines().forEachIndexed { index, raw ->
                val line = raw.trim()
                when {
                    line.isEmpty() -> {}

                    line.startsWith("#") -> {
                        if (line.removePrefix("#").trim() == "requires: password") requiresPassword = true
                    }

                    else -> {
                        steps += step(index + 1, line)
                    }
                }
            }
            return Script(name, requiresPassword, steps)
        }

        fun load(file: File): Script = parse(file.relativeTo(file.parentFile.parentFile).path, file.readText())

        private fun step(
            number: Int,
            line: String,
        ): Step {
            val tag = Regex("""^\[(\w+)]\s*""").find(line)
            val body = if (tag == null) line else line.substring(tag.range.last + 1)
            return when (val name = tag?.groupValues?.get(1)) {
                null -> {
                    Step(number, line, command(body, number), Kind.COMMAND, Normaliser.EXACT)
                }

                "closes" -> {
                    Step(number, line, command(body, number), Kind.CLOSES, Normaliser.EXACT)
                }

                "raw" -> {
                    Step(number, line, unescape(body), Kind.RAW, Normaliser.EXACT)
                }

                else -> {
                    val normaliser =
                        Normaliser.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                            ?: error("line $number: unknown tag [$name]")
                    Step(number, line, command(body, number), Kind.COMMAND, normaliser)
                }
            }
        }

        private fun command(
            body: String,
            number: Int,
        ): ByteArray {
            val args = splitInlineArguments(body.encodeToByteArray()) ?: error("line $number: unbalanced quotes")
            require(args.isNotEmpty()) { "line $number: no command" }
            return Reply.Multi(args.map { Reply.Bulk(it) }).encode()
        }

        /** `\r`, `\n`, `\t`, `\\` and `\xHH`; everything else as written. */
        fun unescape(text: String): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\\' && i + 1 < text.length) {
                    when (text[i + 1]) {
                        'r' -> {
                            out.write('\r'.code)
                        }

                        'n' -> {
                            out.write('\n'.code)
                        }

                        't' -> {
                            out.write('\t'.code)
                        }

                        '\\' -> {
                            out.write('\\'.code)
                        }

                        'x' -> {
                            out.write(text.substring(i + 2, i + 4).toInt(16))
                            i += 2
                        }

                        else -> {
                            out.write(c.code)
                            out.write(text[i + 1].code)
                        }
                    }
                    i += 2
                } else {
                    out.write(c.code)
                    i++
                }
            }
            return out.toByteArray()
        }
    }
}
