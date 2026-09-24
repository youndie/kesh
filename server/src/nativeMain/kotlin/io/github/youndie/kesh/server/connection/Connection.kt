package io.github.youndie.kesh.server.connection

import io.github.youndie.kesh.resp.CommandReader
import io.github.youndie.kesh.resp.ProtocolException
import io.github.youndie.kesh.resp.Reply
import io.github.youndie.kesh.resp.ReplyWriter
import io.github.youndie.kesh.server.client.ClientState
import io.github.youndie.kesh.server.command.CommandDispatcher
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.io.IOException

/**
 * One client connection: read, parse, execute on the store thread, write — in that order, per read.
 *
 * **Batches while authenticated, one command at a time before.** Every command completed by one read
 * is executed in one hand-off to the store thread and answered in one write, so a pipeline of a
 * thousand commands costs a handful of context switches. Before `AUTH` the parser applies the
 * unauthenticated limits, and `AUTH` may change that between two commands of the same read — so
 * until the connection is authenticated each command is parsed only after the previous one ran, as
 * Redis does.
 *
 * The connection closes after the reply when a command asks for it (`QUIT`, `CLIENT KILL` of itself),
 * on a protocol error (answered first), when its unparsed bytes exceed `client-query-buffer-limit`
 * (silently, as Redis does), when the dispatcher drops it (`POST`, `Host:`), and when another client
 * kills it.
 */
internal class Connection(
    private val input: ByteReadChannel,
    private val output: ByteWriteChannel,
    private val client: ClientState,
    private val reader: CommandReader,
    private val queryBufferLimit: Long,
    private val storeThread: CoroutineDispatcher,
    private val commands: CommandDispatcher,
) {
    suspend fun serve() {
        val writer = ReplyWriter()
        val chunk = ByteArray(READ_CHUNK)
        try {
            while (true) {
                val read = input.readAvailable(chunk, 0, chunk.size)
                if (read < 0) return
                reader.feed(chunk, 0, read)
                if (reader.buffered > queryBufferLimit) return
                if (!answerWhatIsComplete(writer)) return
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            // The peer reset or closed mid-read or mid-write, or another client killed this one and
            // closed the socket under it. Either way the connection is over, which is not a failure:
            // Redis logs it at verbose level only. In B-02's 1 100-connection flood, reporting it
            // put 318 "connection failed" lines in the log for nothing.
        }
    }

    /** Executes and answers every complete command; `false` when the connection must now close. */
    private suspend fun answerWhatIsComplete(writer: ReplyWriter): Boolean {
        while (true) {
            val authenticated = client.authenticated
            val batch = ArrayList<List<ByteArray>>()
            var refusal: ProtocolException? = null
            try {
                while (true) {
                    batch += reader.next(authenticated) ?: break
                    if (!authenticated) break
                }
            } catch (e: ProtocolException) {
                refusal = e
            }
            if (batch.isEmpty() && refusal == null) return true

            var keepOpen = true
            if (batch.isNotEmpty()) {
                val replies =
                    withContext(storeThread) {
                        val out = ArrayList<Reply>(batch.size)
                        for (command in batch) {
                            if (client.killed) break
                            val reply = commands.execute(client, command)
                            if (reply == null) {
                                keepOpen = false
                                break
                            }
                            out += reply
                            if (client.closeAfterReply) break
                        }
                        out
                    }
                replies.forEach(writer::write)
                if (client.closeAfterReply || client.killed) keepOpen = false
            }
            refusal?.let {
                writer.write(Reply.Error("ERR ${it.message}"))
                keepOpen = false
            }
            if (writer.length > 0) {
                output.writeFully(writer.toByteArray())
                output.flush()
                writer.clear()
            }
            if (!keepOpen) return false
        }
    }

    private companion object {
        const val READ_CHUNK = 16 * 1024
    }
}
