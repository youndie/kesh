package io.github.youndie.kesh.server.connection

import io.github.youndie.kesh.resp.CommandReader
import io.github.youndie.kesh.resp.ProtocolException
import io.github.youndie.kesh.resp.Reply
import io.github.youndie.kesh.resp.ReplyWriter
import io.github.youndie.kesh.server.command.CommandDispatcher
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * One client connection: read, parse, execute on the store thread, write — in that order, per read.
 *
 * Every command completed by one read is executed in one hand-off to the store thread and answered
 * in one write, so a pipeline of a thousand commands costs a handful of context switches and writes,
 * not a thousand. Replies stay in the order the commands arrived because a batch is executed in
 * order and the next read starts only after the batch is written.
 *
 * A protocol error is answered after the replies to the commands before it, and the connection is
 * closed, as Redis does (`feature-resp-connection`).
 */
internal class Connection(
    private val socket: Socket,
    private val storeThread: CoroutineDispatcher,
    private val commands: CommandDispatcher,
) {
    suspend fun serve() {
        val input = socket.openReadChannel()
        val output = socket.openWriteChannel(autoFlush = false)
        val reader = CommandReader()
        val writer = ReplyWriter()
        val chunk = ByteArray(READ_CHUNK)
        try {
            while (true) {
                val read = input.readAvailable(chunk, 0, chunk.size)
                if (read < 0) break
                reader.feed(chunk, 0, read)

                val batch = ArrayList<List<ByteArray>>()
                var protocolError: ProtocolException? = null
                while (true) {
                    val command =
                        try {
                            reader.next()
                        } catch (e: ProtocolException) {
                            protocolError = e
                            null
                        } ?: break
                    batch += command
                }

                if (batch.isNotEmpty()) {
                    withContext(storeThread) { batch.map(commands::execute) }.forEach(writer::write)
                }
                protocolError?.let { writer.write(Reply.Error("ERR ${it.message}")) }
                if (writer.length > 0) {
                    output.writeFully(writer.toByteArray())
                    output.flush()
                    writer.clear()
                }
                if (protocolError != null) break
            }
        } finally {
            socket.close()
        }
    }

    private companion object {
        const val READ_CHUNK = 16 * 1024
    }
}
