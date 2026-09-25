package io.github.youndie.kesh.store.packed

/**
 * The byte layout of kesh's packed collections — hashes (B-06), list chunks (B-07): items one after
 * another, each an unsigned LEB128 length then its bytes. The role of Redis's listpack, without its
 * integer encodings: an item is always its bytes, so what goes in is what comes out.
 */
internal object Packed {
    val EMPTY = ByteArray(0)

    /** [bytes] with its length prefix. */
    fun encode(bytes: ByteArray): ByteArray {
        var length = bytes.size
        val out = ByteArray(encodedSize(bytes.size))
        var i = 0
        do {
            var b = length and 0x7f
            length = length ushr 7
            if (length != 0) b = b or 0x80
            out[i++] = b.toByte()
        } while (length != 0)
        bytes.copyInto(out, i)
        return out
    }

    /** The bytes one item of [length] takes, prefix included. */
    fun encodedSize(length: Int): Int {
        var n = 1
        var rest = length ushr 7
        while (rest != 0) {
            n++
            rest = rest ushr 7
        }
        return n + length
    }

    /** The length of the item at [at], and the offset its bytes start at, in one `Long`. */
    private fun header(
        data: ByteArray,
        at: Int,
    ): Long {
        var length = 0
        var shift = 0
        var i = at
        while (true) {
            val b = data[i++].toInt() and 0xff
            length = length or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return (length.toLong() shl 32) or i.toLong()
    }

    /** The offset of the item after the one at [at]. */
    fun skip(
        data: ByteArray,
        at: Int,
    ): Int = header(data, at).let { (it and 0xffffffffL).toInt() + (it ushr 32).toInt() }

    /** The offset of the item [n] items after the one at [at]. */
    fun skip(
        data: ByteArray,
        at: Int,
        n: Int,
    ): Int {
        var offset = at
        repeat(n) { offset = skip(data, offset) }
        return offset
    }

    /** A copy of the item at [at]. */
    fun read(
        data: ByteArray,
        at: Int,
    ): ByteArray =
        header(data, at).let {
            val start = (it and 0xffffffffL).toInt()
            data.copyOfRange(start, start + (it ushr 32).toInt())
        }

    /** Whether the item at [at] is [bytes]. */
    fun matches(
        data: ByteArray,
        at: Int,
        bytes: ByteArray,
    ): Boolean {
        val h = header(data, at)
        if ((h ushr 32).toInt() != bytes.size) return false
        val start = (h and 0xffffffffL).toInt()
        for (i in bytes.indices) if (data[start + i] != bytes[i]) return false
        return true
    }

    /** [data] with the bytes from [from] to [to] replaced by [replacement]. */
    fun splice(
        data: ByteArray,
        from: Int,
        to: Int,
        replacement: ByteArray = EMPTY,
    ): ByteArray {
        val out = ByteArray(data.size - (to - from) + replacement.size)
        data.copyInto(out, 0, 0, from)
        replacement.copyInto(out, from)
        data.copyInto(out, from + replacement.size, to, data.size)
        return out
    }
}
