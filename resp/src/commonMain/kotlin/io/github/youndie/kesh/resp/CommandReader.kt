package io.github.youndie.kesh.resp

/**
 * Incremental RESP2 request parser: bytes go in as the socket delivers them, complete commands come
 * out in order.
 *
 * A command split across reads and a buffer holding forty pipelined commands are the same path:
 * [feed] appends, [next] returns the next complete command or `null` when the bytes so far end
 * mid-command. The parser keeps no state between commands other than the unread bytes.
 *
 * **B-01 scope:** arrays of bulk strings only — what every client library and `redis-cli` send.
 * Inline commands, the size limits and the unauthenticated limits are B-02; until then anything that
 * does not start with `*` is refused with Redis's own wording for that case.
 *
 * Not thread-safe; one per connection.
 */
class CommandReader(
    initialCapacity: Int = 16 * 1024,
) {
    private var buffer = ByteArray(initialCapacity)
    private var start = 0
    private var end = 0

    fun feed(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ) {
        compactOrGrow(length)
        bytes.copyInto(buffer, end, offset, offset + length)
        end += length
    }

    /**
     * The next complete command, or `null` if the buffered bytes end before one is complete.
     *
     * @throws ProtocolException on bytes no Redis would accept; the connection must reply with the
     *   exception's message and close, as Redis does.
     */
    fun next(): List<ByteArray>? {
        if (start == end) return null
        var pos = start
        if (buffer[pos] != '*'.code.toByte()) {
            throw ProtocolException("Protocol error: inline commands are not supported yet")
        }
        val countLine = lineEnd(pos + 1) ?: return null
        val count =
            parseLong(pos + 1, countLine)
                ?.takeIf { it <= Int.MAX_VALUE }
                ?: throw ProtocolException("Protocol error: invalid multibulk length")
        pos = countLine + 2
        if (count <= 0) {
            // `*0` and `*-1` are empty requests: Redis skips them and reads on.
            start = pos
            return next()
        }
        val args = ArrayList<ByteArray>(count.toInt())
        repeat(count.toInt()) {
            if (pos >= end) return null
            if (buffer[pos] != '$'.code.toByte()) {
                throw ProtocolException("Protocol error: expected '$', got '${buffer[pos].toInt().toChar()}'")
            }
            val lengthLine = lineEnd(pos + 1) ?: return null
            val length =
                parseLong(pos + 1, lengthLine)
                    ?.takeIf { it >= 0 && it <= Int.MAX_VALUE }
                    ?: throw ProtocolException("Protocol error: invalid bulk length")
            val dataStart = lengthLine + 2
            val dataEnd = dataStart + length.toInt()
            if (dataEnd + 2 > end) return null
            args += buffer.copyOfRange(dataStart, dataEnd)
            pos = dataEnd + 2
        }
        start = pos
        return args
    }

    /** Index of the CR of the next CRLF at or after [from], or `null` if the line is not complete yet. */
    private fun lineEnd(from: Int): Int? {
        var i = from
        while (i + 1 < end) {
            if (buffer[i] == '\r'.code.toByte() && buffer[i + 1] == '\n'.code.toByte()) return i
            i++
        }
        return null
    }

    private fun parseLong(
        from: Int,
        until: Int,
    ): Long? {
        if (from >= until || until - from > 19) return null
        var i = from
        val negative = buffer[i] == '-'.code.toByte()
        if (negative) i++
        if (i == until) return null
        var value = 0L
        while (i < until) {
            val digit = buffer[i] - '0'.code.toByte()
            if (digit !in 0..9) return null
            value = value * 10 + digit
            i++
        }
        return if (negative) -value else value
    }

    private fun compactOrGrow(incoming: Int) {
        if (end + incoming <= buffer.size) return
        val unread = end - start
        if (unread + incoming <= buffer.size) {
            buffer.copyInto(buffer, 0, start, end)
        } else {
            var capacity = buffer.size
            while (capacity < unread + incoming) capacity *= 2
            buffer = buffer.copyOfRange(start, end).copyOf(capacity)
        }
        start = 0
        end = unread
    }
}

/** A request no Redis would accept. [message] is the reply, without the `ERR ` prefix. */
class ProtocolException(
    override val message: String,
) : Exception(message)
