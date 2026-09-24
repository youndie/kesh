package io.github.youndie.kesh.resp

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail

/**
 * Whatever arrives, in whatever pieces, the reader answers with a command, `null` or a
 * [ProtocolException] — never another exception, which on the server would end the connection
 * without the reply Redis sends, or worse. Seeded, so a failure names the case that reproduces it.
 */
class CommandReaderFuzzTest {
    private val alphabet = "*$\r\n0123456789-+:PINGSET \"'\\x".encodeToByteArray()

    private fun input(random: Random): ByteArray {
        val valid = "*2\r\n$3\r\nGET\r\n$1\r\nk\r\nPING\r\n".encodeToByteArray()
        return ByteArray(random.nextInt(1, 200)) {
            when (random.nextInt(4)) {
                0 -> random.nextInt(256).toByte()
                1 -> alphabet[random.nextInt(alphabet.size)]
                else -> valid[it % valid.size]
            }
        }
    }

    @Test
    fun `random input never throws anything but a protocol error`() {
        repeat(20_000) { case ->
            val random = Random(case)
            val bytes = input(random)
            val reader = CommandReader(RequestLimits(inlineMaxSize = 64, protoMaxBulkLen = 64), initialCapacity = 8)
            var offset = 0
            try {
                while (offset < bytes.size) {
                    val piece = random.nextInt(1, bytes.size - offset + 1)
                    reader.feed(bytes, offset, piece)
                    offset += piece
                    while (true) reader.next(authenticated = random.nextBoolean()) ?: break
                }
            } catch (_: ProtocolException) {
                // Refused, as Redis would refuse it: the connection answers and closes.
            } catch (e: Throwable) {
                fail("case $case threw $e for ${bytes.toList()}")
            }
        }
    }
}
