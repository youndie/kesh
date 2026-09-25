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
 * [random a b c] SPOP s 2     a random reply: members of the population named, as many as Redis's
 * [info evicted_keys] INFO     only these fields' lines of the report are compared
 * @sub SUBSCRIBE news          on the script's connection named `sub` (opened when first used)
 * [frames 2] SUBSCRIBE a b     two replies, compared as one array; `[frames 2 unordered]` as a multiset
 * [read 1] @sub                sends nothing; reads what the server pushed to `sub`
 * [fields used_memory] INFO    the report's sections and field names, which Redis must have too
 * [cursor] SCAN 0 COUNT 5     iterated to cursor 0 on each server; the unions are compared
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
    data class Step(
        val line: Int,
        val source: String,
        val bytes: ByteArray,
        val kind: Kind,
        val normaliser: Normaliser,
        /** What a [Normaliser.RANDOM] reply may contain; empty for every other normaliser. */
        val population: Set<List<Byte>> = emptySet(),
        /** The command's arguments, for a [Kind.CURSOR] step that rewrites its cursor as it goes. */
        val arguments: List<ByteArray> = emptyList(),
        /** The fields a [Normaliser.INFO] step compares. */
        val fields: List<String> = emptyList(),
        /** Which of the script's connections the step uses: `@name` on the line; `main` without one. */
        val connection: String = MAIN,
        /** How many replies the step reads — `[frames N]`, `[read N]`; more than one are compared as one array. */
        val frames: Int = 1,
    )

    /** `READ` sends nothing and reads what the server pushes: a subscriber's messages. */
    enum class Kind { COMMAND, CLOSES, RAW, CURSOR, READ }

    companion object {
        const val MAIN = "main"

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
            val tag = Regex("""^\[(\w+)([^\]]*)]\s*""").find(line)
            val afterTag = if (tag == null) line else line.substring(tag.range.last + 1)
            val at = Regex("""^@(\w+)\s*""").find(afterTag)
            val connection = at?.groupValues?.get(1) ?: MAIN
            val body = if (at == null) afterTag else afterTag.substring(at.range.last + 1)
            return parsed(number, line, tag, body).copy(connection = connection)
        }

        private fun parsed(
            number: Int,
            line: String,
            tag: MatchResult?,
            body: String,
        ): Step {
            val arguments =
                tag
                    ?.groupValues
                    ?.get(
                        2,
                    )?.trim()
                    ?.split(Regex("""\s+"""))
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
            val name = tag?.groupValues?.get(1)
            require(arguments.isEmpty() || name in setOf("random", "info", "fields", "frames", "read")) {
                "line $number: only [random], [info], [fields], [frames] and [read] take arguments"
            }
            if (name == "frames" || name == "read") {
                val count =
                    arguments.firstOrNull()?.toIntOrNull() ?: error("line $number: [$name] needs how many replies")
                val normaliser = if ("unordered" in arguments.drop(1)) Normaliser.UNORDERED else Normaliser.EXACT
                return if (name == "read") {
                    require(body.isBlank()) { "line $number: [read] sends nothing" }
                    Step(number, line, ByteArray(0), Kind.READ, normaliser, frames = count)
                } else {
                    Step(number, line, command(body, number), Kind.COMMAND, normaliser, frames = count)
                }
            }
            if (name == "info" || name == "fields") {
                require(arguments.isNotEmpty()) { "line $number: [$name] needs the fields it compares" }
                val normaliser = if (name == "info") Normaliser.INFO else Normaliser.FIELDS
                return Step(number, line, command(body, number), Kind.COMMAND, normaliser, fields = arguments)
            }
            if (name == "cursor") {
                val args = splitInlineArguments(body.encodeToByteArray()) ?: error("line $number: unbalanced quotes")
                return Step(number, line, command(body, number), Kind.CURSOR, Normaliser.CURSOR, arguments = args)
            }
            if (name == "random") {
                require(arguments.isNotEmpty()) { "line $number: [random] needs the population it draws from" }
                val population = arguments.map { it.encodeToByteArray().toList() }.toSet()
                return Step(number, line, command(body, number), Kind.COMMAND, Normaliser.RANDOM, population)
            }
            return when (name) {
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
