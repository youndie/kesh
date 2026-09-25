package io.github.youndie.kesh.conformance

import io.github.youndie.kesh.resp.Reply
import io.github.youndie.kesh.resp.encode
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** Where a server listens. */
data class Endpoint(
    val host: String,
    val port: Int,
) {
    override fun toString(): String = "$host:$port"

    companion object {
        fun parse(text: String): Endpoint {
            val (host, port) = text.split(':', limit = 2)
            return Endpoint(host, port.toInt())
        }
    }
}

/** One compared step: what each side answered and whether they agree under the step's normaliser. */
class Outcome(
    val script: String,
    val step: Script.Step,
    val kesh: ByteArray,
    val oracle: ByteArray,
    val agree: Boolean,
)

/**
 * Runs a script against kesh and the oracle side by side: each step is sent to both, each reply read
 * from both, and the two compared. A step that closes the connection (a `[closes]` step, or a `[raw]`
 * one that the server ends) is followed by a fresh connection on both sides.
 */
class Runner(
    private val kesh: Endpoint,
    private val oracle: Endpoint,
    private val quietAfter: Int = 300,
) {
    fun run(script: Script): List<Outcome> {
        var k = Connection(kesh)
        var o = Connection(oracle)
        val outcomes = ArrayList<Outcome>()
        try {
            for (step in script.steps) {
                val (kReply, kClosed) = k.safely(step)
                val (oReply, oClosed) = o.safely(step)
                // `[closes]` is a claim about both servers, not only that they behave alike: two servers
                // that both stay open would otherwise agree on a line that says they close.
                val closesAsClaimed = step.kind != Script.Kind.CLOSES || (kClosed && oClosed)
                val agree =
                    kClosed == oClosed && closesAsClaimed &&
                        when (step.kind) {
                            Script.Kind.RAW -> {
                                kReply.contentEquals(oReply)
                            }

                            else -> {
                                runCatching {
                                    step.normaliser.agree(
                                        RespFrame.read(kReply.inputStream()),
                                        RespFrame.read(oReply.inputStream()),
                                        step.population,
                                    )
                                }.getOrDefault(false)
                            }
                        }
                outcomes +=
                    Outcome(script.name, step, kReply + closedMark(kClosed), oReply + closedMark(oClosed), agree)
                if (kClosed || oClosed) {
                    k.close()
                    o.close()
                    k = Connection(kesh)
                    o = Connection(oracle)
                }
            }
        } finally {
            k.close()
            o.close()
        }
        return outcomes
    }

    private fun closedMark(closed: Boolean) = if (closed) CLOSED else ByteArray(0)

    private inner class Connection(
        endpoint: Endpoint,
    ) : AutoCloseable {
        private val socket = Socket().apply { connect(InetSocketAddress(endpoint.host, endpoint.port), 2_000) }
        private val input: InputStream = BufferedInputStream(socket.getInputStream())

        init {
            // Unauthenticated, always: a script against the locked pair tests what happens before AUTH
            // and sends AUTH itself.
            socket.soTimeout = 5_000
        }

        /**
         * [exchange], with a reply that cannot be read — a RESP3 type, a stream that ends mid-reply —
         * turned into a disagreement that names the failure and a closed connection, so one broken
         * step is reported and the run carries on.
         */
        fun safely(step: Script.Step): Pair<ByteArray, Boolean> =
            try {
                exchange(step)
            } catch (e: Exception) {
                "<unreadable: ${e.message}>".encodeToByteArray() to true
            }

        /** The reply's bytes, and whether the server closed the connection after it. */
        fun exchange(step: Script.Step): Pair<ByteArray, Boolean> {
            if (step.kind == Script.Kind.CURSOR) return iterate(step)
            socket.getOutputStream().apply {
                write(step.bytes)
                flush()
            }
            return when (step.kind) {
                Script.Kind.COMMAND -> RespFrame.read(input).bytes to false
                Script.Kind.CLOSES -> RespFrame.read(input).bytes to endsNow()
                Script.Kind.RAW -> drain()
                Script.Kind.CURSOR -> error("a [cursor] step is iterated, not exchanged once")
            }
        }

        /**
         * A `[cursor]` step: the command sent again with each cursor the server returns until it
         * returns 0, and the union of the elements as one synthetic array reply — sorted, without
         * repeats, `HSCAN`'s and `ZSCAN`'s pairs kept together. A reply that is not a scan reply (an
         * error) is returned as it came.
         */
        private fun iterate(step: Script.Step): Pair<ByteArray, Boolean> {
            val args = step.arguments.toMutableList()
            val name = args[0].decodeToString().lowercase()
            val cursorAt = if (name == "scan") 1 else 2
            val group = if (name == "hscan" || name == "zscan") 2 else 1
            val units = java.util.TreeSet<String>()
            var bulks = 0
            repeat(MAX_ROUNDS) {
                socket.getOutputStream().apply {
                    write(Reply.Multi(args.map { Reply.Bulk(it) }).encode())
                    flush()
                }
                val frame = RespFrame.read(input)
                val parts = frame.elements
                if (frame.type != '*' || parts == null || parts.size != 2 ||
                    parts[1].elements == null
                ) {
                    return frame.bytes to false
                }
                for (unit in parts[1].elements!!.chunked(group)) {
                    if (units.add(unit.joinToString("") { String(it.bytes, Charsets.ISO_8859_1) })) bulks += unit.size
                }
                val cursor = parts[0].bulkPayload()!!
                if (cursor.decodeToString() == "0") {
                    val body = units.joinToString("").toByteArray(Charsets.ISO_8859_1)
                    return "*$bulks\r\n".encodeToByteArray() + body to false
                }
                args[cursorAt] = cursor
            }
            error("no end of iteration after $MAX_ROUNDS rounds")
        }

        /** Everything the server sends until it closes (true) or stays quiet for [quietAfter] ms (false). */
        private fun drain(): Pair<ByteArray, Boolean> {
            val out = ByteArrayOutputStream()
            socket.soTimeout = quietAfter
            try {
                while (true) {
                    val b = input.read()
                    if (b < 0) return out.toByteArray() to true
                    out.write(b)
                }
            } catch (_: SocketTimeoutException) {
                return out.toByteArray() to false
            } catch (_: java.net.SocketException) {
                return out.toByteArray() to true
            } finally {
                socket.soTimeout = 5_000
            }
        }

        private fun endsNow(): Boolean {
            socket.soTimeout = quietAfter
            return try {
                input.read() < 0
            } catch (_: SocketTimeoutException) {
                false
            } catch (_: EOFException) {
                true
            } catch (_: java.net.SocketException) {
                true
            } finally {
                socket.soTimeout = 5_000
            }
        }

        override fun close() = socket.close()
    }

    companion object {
        /** Appended to a reply after which the server closed the connection, so the diff shows it. */
        val CLOSED = "<closed>".encodeToByteArray()

        /** How many calls a `[cursor]` iteration may take before the harness calls it endless. */
        const val MAX_ROUNDS = 1_000_000

        /** Both byte streams from the first offset where they differ, with a little context before it. */
        fun describe(outcome: Outcome): String {
            val a = outcome.kesh
            val b = outcome.oracle
            var offset = 0
            while (offset < a.size && offset < b.size && a[offset] == b[offset]) offset++
            val from = maxOf(0, offset - 16)

            fun tail(x: ByteArray) = RespFrame.escape(x.copyOfRange(minOf(from, x.size), minOf(x.size, offset + 160)))
            return buildString {
                appendLine("${outcome.script}:${outcome.step.line}  ${outcome.step.source}")
                appendLine(
                    "  first difference at byte $offset (${outcome.step.normaliser.name.lowercase()} comparison)",
                )
                appendLine("  kesh:   ${if (from > 0) "…" else ""}${tail(a)}")
                append("  oracle: ${if (from > 0) "…" else ""}${tail(b)}")
            }
        }
    }
}
