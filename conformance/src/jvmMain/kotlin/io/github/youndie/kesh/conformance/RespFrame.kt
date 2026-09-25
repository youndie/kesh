package io.github.youndie.kesh.conformance

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/**
 * One RESP2 reply exactly as it came off the wire, plus enough structure to normalise it.
 *
 * [bytes] are the reply's own bytes, nothing more and nothing less — the unit of "byte for byte".
 */
class RespFrame(
    val bytes: ByteArray,
    val type: Char,
    /** For `*`: the elements, `null` for the null array. Empty for every other type. */
    val elements: List<RespFrame>?,
) {
    override fun toString(): String = escape(bytes)

    /** For `$`: the string's own bytes, or `null` for the null bulk string. */
    fun bulkPayload(): ByteArray? {
        check(type == '$') { "not a bulk string" }
        val headerEnd = bytes.indexOf('\r'.code.toByte())
        val length = bytes.decodeToString(1, headerEnd).toInt()
        return if (length < 0) null else bytes.copyOfRange(headerEnd + 2, headerEnd + 2 + length)
    }

    companion object {
        /** Reads exactly one reply; throws [EOFException] if the stream ends before it is complete. */
        fun read(input: InputStream): RespFrame {
            val out = ByteArrayOutputStream()
            val frame = readInto(input, out)
            return RespFrame(out.toByteArray(), frame.type, frame.elements)
        }

        private fun readInto(
            input: InputStream,
            out: ByteArrayOutputStream,
        ): RespFrame {
            val start = out.size()
            val type = input.readByte(out).toInt().toChar()
            val line = input.readLine(out)
            val elements: List<RespFrame>? =
                when (type) {
                    '+', '-', ':' -> {
                        emptyList()
                    }

                    '$' -> {
                        val length = line.toInt()
                        if (length >= 0) repeat(length + 2) { input.readByte(out) }
                        emptyList()
                    }

                    '*' -> {
                        val count = line.toInt()
                        if (count < 0) null else List(count) { readInto(input, out) }
                    }

                    else -> {
                        error("not a RESP2 reply type: '$type'")
                    }
                }
            return RespFrame(out.toByteArray().copyOfRange(start, out.size()), type, elements)
        }

        private fun InputStream.readByte(out: ByteArrayOutputStream): Byte {
            val b = read()
            if (b < 0) throw EOFException("the stream ended inside a reply")
            out.write(b)
            return b.toByte()
        }

        /** The rest of a header line, without its CRLF (which is copied to [out]). */
        private fun InputStream.readLine(out: ByteArrayOutputStream): String {
            val text = StringBuilder()
            while (true) {
                val b = readByte(out).toInt().toChar()
                if (b == '\r') {
                    readByte(out)
                    return text.toString()
                }
                text.append(b)
            }
        }

        /** Printable, with CR, LF and non-ASCII bytes written as escapes, so a diff can be read. */
        fun escape(bytes: ByteArray): String =
            buildString {
                for (b in bytes) {
                    val c = b.toInt() and 0xFF
                    when {
                        c == '\r'.code -> append("\\r")
                        c == '\n'.code -> append("\\n")
                        c == '\\'.code -> append("\\\\")
                        c in 0x20..0x7e -> append(c.toChar())
                        else -> append("\\x%02x".format(c))
                    }
                }
            }
    }
}
