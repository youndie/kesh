package io.github.youndie.kesh.resp

/**
 * The limits Redis 7.2 applies while reading a request (`redis/redis@7.2!/src/networking.c`,
 * `processInlineBuffer` and `processMultibulkBuffer`; the constants in `server.h`).
 *
 * @property protoMaxBulkLen `proto-max-bulk-len`: the largest bulk string, 512 MB by default.
 * @property inlineMaxSize `PROTO_INLINE_MAX_SIZE`: how far a line may run without its line ending —
 *   an inline command, or the count line of a multibulk request or of a bulk string.
 * @property unauthenticatedMaxArgs before `AUTH`, a multibulk request may carry at most this many.
 * @property unauthenticatedMaxBulk before `AUTH`, a bulk string may be at most this long.
 */
data class RequestLimits(
    val protoMaxBulkLen: Long = 512L * 1024 * 1024,
    val inlineMaxSize: Int = 64 * 1024,
    val unauthenticatedMaxArgs: Long = 10,
    val unauthenticatedMaxBulk: Long = 16 * 1024,
)

/**
 * Incremental RESP2 request parser: bytes go in as the socket delivers them, complete commands come
 * out in order.
 *
 * A command split across reads and a buffer holding a thousand pipelined commands are the same path:
 * [feed] appends, [next] returns the next complete command or `null` when the bytes so far end
 * mid-command. Two forms, as in Redis: a multibulk request (`*` first — every client library and
 * `redis-cli`) and an inline command (anything else — a person in `telnet`), split by
 * [splitInlineArguments].
 *
 * Every refusal is a [ProtocolException] carrying Redis's own words for that condition; the
 * connection answers it and closes. The parser checks a limit the moment the line that breaks it is
 * complete, as Redis does, so an oversized bulk is refused from its header, before its data arrives.
 *
 * Not thread-safe; one per connection.
 */
class CommandReader(
    private val limits: RequestLimits = RequestLimits(),
    initialCapacity: Int = 16 * 1024,
) {
    private var buffer = ByteArray(initialCapacity)
    private var start = 0
    private var end = 0

    /** Bytes received and not yet returned as a command — what `client-query-buffer-limit` bounds. */
    val buffered: Int get() = end - start

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
     * The next complete command, or `null` if the buffered bytes end before one is complete. Empty
     * requests (a blank inline line, `*0`, `*-1`) are skipped, as Redis skips them.
     *
     * @param authenticated whether the connection has passed `AUTH` (or needs none). Before it, the
     *   two unauthenticated limits of [RequestLimits] apply. Passed per call because `AUTH` changes it
     *   between two commands of the same pipeline.
     * @throws ProtocolException on bytes Redis would refuse.
     */
    fun next(authenticated: Boolean = true): List<ByteArray>? {
        while (start < end) {
            val command =
                if (buffer[start] == '*'.code.toByte()) multibulk(authenticated) else inline()
            when (command) {
                null -> return null
                EMPTY -> continue
                else -> return command
            }
        }
        return null
    }

    private fun inline(): List<ByteArray>? {
        val newline = indexOf('\n', start)
        if (newline == null) {
            if (end - start > limits.inlineMaxSize) throw ProtocolException("Protocol error: too big inline request")
            return null
        }
        val lineEnd = if (newline > start && buffer[newline - 1] == '\r'.code.toByte()) newline - 1 else newline
        val args =
            splitInlineArguments(buffer.copyOfRange(start, lineEnd))
                ?: throw ProtocolException("Protocol error: unbalanced quotes in request")
        start = newline + 1
        return args.ifEmpty { EMPTY }
    }

    private fun multibulk(authenticated: Boolean): List<ByteArray>? {
        val countEnd = lineEnd(start + 1, "Protocol error: too big mbulk count string") ?: return null
        val count =
            parseRedisLong(buffer, start + 1, countEnd)
                ?.takeIf { it <= Int.MAX_VALUE }
                ?: throw ProtocolException("Protocol error: invalid multibulk length")
        if (count > limits.unauthenticatedMaxArgs && !authenticated) {
            throw ProtocolException("Protocol error: unauthenticated multibulk length")
        }
        var pos = countEnd + 2
        if (count <= 0) {
            start = pos
            return EMPTY
        }
        val args = ArrayList<ByteArray>(minOf(count, PREALLOCATE_LIMIT).toInt())
        repeat(count.toInt()) {
            if (pos >= end) return null
            if (buffer[pos] != '$'.code.toByte()) {
                throw ProtocolException("Protocol error: expected '$', got '${buffer[pos].toInt().toChar()}'")
            }
            val lengthEnd = lineEnd(pos + 1, "Protocol error: too big bulk count string") ?: return null
            val length =
                parseRedisLong(buffer, pos + 1, lengthEnd)
                    ?.takeIf { it >= 0 && it <= limits.protoMaxBulkLen }
                    ?: throw ProtocolException("Protocol error: invalid bulk length")
            if (length > limits.unauthenticatedMaxBulk && !authenticated) {
                throw ProtocolException("Protocol error: unauthenticated bulk length")
            }
            val dataStart = lengthEnd + 2
            if (dataStart.toLong() + length + 2 > end) return null
            val dataEnd = dataStart + length.toInt()
            args += buffer.copyOfRange(dataStart, dataEnd)
            pos = dataEnd + 2
        }
        start = pos
        return args
    }

    /**
     * Index of the `\r` ending the count line that starts at [from], once the byte after it has
     * arrived too; `null` while the line is incomplete. Like Redis, it looks for `\r` and skips two
     * bytes. A line longer than `PROTO_INLINE_MAX_SIZE` without one is refused with [tooBig].
     */
    private fun lineEnd(
        from: Int,
        tooBig: String,
    ): Int? {
        val cr = indexOf('\r', from)
        if (cr == null) {
            if (end - from > limits.inlineMaxSize) throw ProtocolException(tooBig)
            return null
        }
        return if (cr + 1 < end) cr else null
    }

    private fun indexOf(
        char: Char,
        from: Int,
    ): Int? {
        val target = char.code.toByte()
        for (i in from until end) if (buffer[i] == target) return i
        return null
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

    private companion object {
        /** A request that asks for nothing; skipped by [next]. Any empty list is one. */
        val EMPTY: List<ByteArray> = ArrayList(0)

        /** A count is the client's claim; the list grows to it only as the arguments arrive. */
        const val PREALLOCATE_LIMIT = 1024L
    }
}

/** A request Redis would refuse. [message] is the reply, without the `ERR ` prefix. */
class ProtocolException(
    override val message: String,
) : Exception(message)
