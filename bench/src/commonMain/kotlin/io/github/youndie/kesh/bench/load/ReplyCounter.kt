package io.github.youndie.kesh.bench.load

/**
 * Counts whole RESP2 replies in a byte stream fed in pieces, and the error replies among them —
 * without keeping them. Nested arrays (`HGETALL`, `LRANGE`, `ZREVRANGE WITHSCORES`) count as one.
 */
class ReplyCounter {
    var errors = 0L
        private set

    private var pending = ByteArray(64 * 1024)
    private var pendingLength = 0

    /** Feeds [length] bytes; returns how many replies they completed. */
    fun feed(
        bytes: ByteArray,
        length: Int,
    ): Int {
        if (pendingLength + length >
            pending.size
        ) {
            pending = pending.copyOf(maxOf(pending.size * 2, pendingLength + length))
        }
        bytes.copyInto(pending, pendingLength, 0, length)
        pendingLength += length
        var at = 0
        var done = 0
        while (true) {
            val end = reply(at) ?: break
            if (pending[at] == '-'.code.toByte()) errors++
            at = end
            done++
        }
        pending.copyInto(pending, 0, at, pendingLength)
        pendingLength -= at
        return done
    }

    /** The end of the reply starting at [at], or `null` if it is not all here yet. */
    private fun reply(at: Int): Int? {
        if (at >= pendingLength) return null
        val lineEnd = lineEnd(at) ?: return null
        return when (pending[at].toInt().toChar()) {
            '+', '-', ':' -> {
                lineEnd
            }

            '$' -> {
                val n = number(at + 1, lineEnd - 2)
                if (n < 0) lineEnd else (lineEnd + n + 2).takeIf { it <= pendingLength }?.toInt()
            }

            '*' -> {
                val n = number(at + 1, lineEnd - 2)
                var end = lineEnd
                repeat(maxOf(n, 0L).toInt()) { end = reply(end) ?: return null }
                end
            }

            else -> {
                error("not a RESP2 reply at $at: ${pending[at]}")
            }
        }
    }

    private fun lineEnd(from: Int): Int? {
        var i = from
        while (i + 1 < pendingLength) {
            if (pending[i] == '\r'.code.toByte() && pending[i + 1] == '\n'.code.toByte()) return i + 2
            i++
        }
        return null
    }

    private fun number(
        from: Int,
        until: Int,
    ): Long = pending.decodeToString(from, until).toLong()
}
