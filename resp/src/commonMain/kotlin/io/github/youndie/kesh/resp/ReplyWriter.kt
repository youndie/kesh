package io.github.youndie.kesh.resp

/**
 * Encodes replies into one growing byte buffer, so that a whole pipelined batch becomes one write.
 *
 * Not thread-safe; one per connection.
 */
class ReplyWriter(
    initialCapacity: Int = 4096,
) {
    private var buffer = ByteArray(initialCapacity)
    private var size = 0

    val length: Int get() = size

    fun write(reply: Reply) {
        when (reply) {
            is Reply.Simple -> {
                line('+', reply.text)
            }

            is Reply.Error -> {
                line('-', reply.message)
            }

            is Reply.Integer -> {
                line(':', reply.value.toString())
            }

            is Reply.Bulk -> {
                val bytes = reply.bytes
                if (bytes == null) {
                    line('$', "-1")
                } else {
                    line('$', bytes.size.toString())
                    append(bytes)
                    append(CRLF)
                }
            }

            is Reply.Multi -> {
                val items = reply.items
                if (items == null) {
                    line('*', "-1")
                } else {
                    line('*', items.size.toString())
                    items.forEach(::write)
                }
            }

            is Reply.Frames -> {
                reply.replies.forEach(::write)
            }
        }
    }

    /** The encoded bytes so far; the array is a copy the caller owns. */
    fun toByteArray(): ByteArray = buffer.copyOf(size)

    fun clear() {
        size = 0
    }

    private fun line(
        prefix: Char,
        text: String,
    ) {
        ensure(1)
        buffer[size++] = prefix.code.toByte()
        append(text.encodeToByteArray())
        append(CRLF)
    }

    private fun append(bytes: ByteArray) {
        ensure(bytes.size)
        bytes.copyInto(buffer, size)
        size += bytes.size
    }

    private fun ensure(extra: Int) {
        if (size + extra <= buffer.size) return
        var capacity = buffer.size
        while (capacity < size + extra) capacity *= 2
        buffer = buffer.copyOf(capacity)
    }

    private companion object {
        val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
    }
}

/** One reply as bytes. Tests and one-off writes; a connection keeps a [ReplyWriter]. */
fun Reply.encode(): ByteArray = ReplyWriter(64).also { it.write(this) }.toByteArray()
