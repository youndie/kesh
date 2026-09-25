package io.github.youndie.kesh.snapshot

/** CRC-32 (IEEE 802.3, the zlib one), table-driven: the snapshot's check that a file is whole. */
class Crc32 {
    private var crc = 0xffffffff.toInt()

    val value: Int get() = crc.inv()

    fun update(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size,
    ) {
        var c = crc
        for (i in offset until offset + length) c = TABLE[(c xor bytes[i].toInt()) and 0xff] xor (c ushr 8)
        crc = c
    }

    private companion object {
        val TABLE =
            IntArray(256) { n ->
                var c = n
                repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xedb88320.toInt() else c ushr 1 }
                c
            }
    }
}
