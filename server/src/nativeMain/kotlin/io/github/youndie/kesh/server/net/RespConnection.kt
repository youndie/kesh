package io.github.youndie.kesh.server.net

import io.github.youndie.kesh.resp.CommandReader
import io.github.youndie.kesh.resp.ProtocolException
import io.github.youndie.kesh.resp.Reply
import io.github.youndie.kesh.resp.ReplyWriter
import io.github.youndie.kesh.server.client.ClientState
import io.github.youndie.kesh.server.command.CommandDispatcher
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.linux.EPOLLERR
import platform.linux.EPOLLHUP
import platform.linux.EPOLLIN
import platform.linux.EPOLLOUT
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.EWOULDBLOCK
import platform.posix.close
import platform.posix.errno
import platform.posix.read
import platform.posix.write
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * One RESP connection on the [EventLoop] (research D-31): read into a buffer kept for the
 * connection's life, parse, execute each command right there — the loop's thread is the store
 * thread — and write the replies; `EPOLLOUT` is asked for only while replies are waiting for the
 * socket. Nothing is allocated per read but the parsed arguments and the replies themselves.
 *
 * The connection closes after the reply when a command asks for it (`QUIT`, `CLIENT KILL` of
 * itself), on a protocol error (answered first), when its unparsed bytes exceed
 * `client-query-buffer-limit` (silently, as Redis does), when the dispatcher drops it (`POST`,
 * `Host:`), and when another client kills it. Before `AUTH` each command is parsed only after the
 * previous one ran, so `AUTH` lifts the unauthenticated limits for the very next command (B-02).
 */
@OptIn(ExperimentalForeignApi::class)
internal class RespConnection(
    val fd: Int,
    private val loop: EventLoop,
    private val reader: CommandReader,
    private val queryBufferLimit: Long,
    private val commands: CommandDispatcher,
    /** Called once, after the descriptor is closed. */
    private val closed: (RespConnection) -> Unit,
) : EventLoop.Handler {
    lateinit var client: ClientState

    private val chunk = ByteArray(READ_CHUNK)
    private val writer = ReplyWriter()
    private val pushes = ReplyWriter()

    /** Since when the output has been over the soft pub/sub limit; `null` while it is not. */
    private var overSoftSince: TimeMark? = null
    private var pending = ByteArray(0)
    private var pendingFrom = 0
    private var pendingTo = 0
    private var writing = false
    private var closeWhenWritten = false
    private var open = true

    /** Replies not yet taken by the socket — what the drain waits on (B-16). */
    val hasPendingOutput: Boolean get() = pendingTo > pendingFrom

    override fun onEvent(events: UInt) {
        if (!open) return
        if (events and (EPOLLIN or EPOLLHUP or EPOLLERR) != 0u) readAvailable()
        if (open && events and EPOLLOUT != 0u) flush()
    }

    /**
     * A published message for this client (B-27): queued behind whatever it has not been sent yet,
     * then held to Redis's pub/sub output limit — the bytes queued, counted here, since nothing
     * pushes back on a queue (research D-29).
     */
    fun deliver(message: Reply) {
        if (!open) return
        pushes.write(message)
        append(pushes.toByteArray())
        pushes.clear()
        if (overOutputLimit()) {
            println("kesh: client ${client.address} closed for exceeding the pub/sub output buffer limit")
            close()
            return
        }
        flush()
    }

    /**
     * `checkClientOutputBufferLimits` for the pub/sub class, Redis 7.2's defaults
     * (`clientBufferLimitsDefaults`, `redis/redis@7.2!/src/config.c`): 32 MB at once, or 8 MB for 60 s.
     */
    private fun overOutputLimit(): Boolean {
        if (client.subscriptions == 0) return false
        val queued = pendingTo - pendingFrom
        if (queued > PUBSUB_HARD_LIMIT) return true
        if (queued <= PUBSUB_SOFT_LIMIT) {
            overSoftSince = null
            return false
        }
        val since = overSoftSince ?: TimeSource.Monotonic.markNow().also { overSoftSince = it }
        return since.elapsedNow() > PUBSUB_SOFT_SECONDS
    }

    /** Ends the connection once what it was told has been written: the drain's first step (B-16). */
    fun closeAfterWriting() {
        if (!hasPendingOutput) close() else closeWhenWritten = true
    }

    fun close() {
        if (!open) return
        open = false
        loop.remove(fd)
        close(fd)
        closed(this)
    }

    private fun readAvailable() {
        // A few reads per turn, so one busy connection does not hold the loop: `epoll` is
        // level-triggered and calls again while bytes remain.
        repeat(READS_PER_TURN) {
            if (closeWhenWritten) return
            val n = chunk.usePinned { read(fd, it.addressOf(0), READ_CHUNK.toULong()) }
            when {
                n > 0 -> {
                    reader.feed(chunk, 0, n.toInt())
                    if (reader.buffered > queryBufferLimit) {
                        close()
                        return
                    }
                    if (!answer()) return
                }

                n == 0L -> {
                    close()
                    return
                }

                errno == EAGAIN || errno == EWOULDBLOCK -> {
                    return
                }

                errno == EINTR -> {}

                else -> {
                    close()
                    return
                }
            }
        }
    }

    /** Executes and answers every complete command; `false` when the connection is closing. */
    private fun answer(): Boolean {
        var keepOpen = true
        while (keepOpen) {
            val command =
                try {
                    reader.next(client.authenticated) ?: break
                } catch (e: ProtocolException) {
                    writer.write(Reply.Error("ERR ${e.message}"))
                    keepOpen = false
                    break
                }
            if (client.killed) {
                keepOpen = false
                break
            }
            val reply = commands.execute(client, command)
            if (reply == null) {
                // Dropped without a reply (research D-13's security rule): what was answered before
                // it is still written.
                keepOpen = false
                break
            }
            writer.write(reply)
            if (client.closeAfterReply || client.killed) keepOpen = false
        }
        if (writer.length > 0) {
            append(writer.toByteArray())
            writer.clear()
        }
        if (!keepOpen) closeWhenWritten = true
        flush()
        return open && !closeWhenWritten
    }

    private fun append(bytes: ByteArray) {
        if (pendingTo == pendingFrom) {
            pendingFrom = 0
            pendingTo = 0
        }
        if (pendingTo + bytes.size > pending.size) {
            val live = pendingTo - pendingFrom
            val grown = ByteArray(maxOf(pending.size * 2, live + bytes.size, 16 * 1024))
            pending.copyInto(grown, 0, pendingFrom, pendingTo)
            pending = grown
            pendingFrom = 0
            pendingTo = live
        }
        bytes.copyInto(pending, pendingTo)
        pendingTo += bytes.size
    }

    private fun flush() {
        while (pendingTo > pendingFrom) {
            val n =
                pending.usePinned {
                    write(fd, it.addressOf(pendingFrom), (pendingTo - pendingFrom).toULong())
                }
            when {
                n > 0 -> {
                    pendingFrom += n.toInt()
                }

                errno == EAGAIN || errno == EWOULDBLOCK -> {
                    if (!writing) {
                        writing = true
                        loop.modify(fd, (EPOLLIN or EPOLLOUT))
                    }
                    return
                }

                errno == EINTR -> {}

                else -> {
                    close()
                    return
                }
            }
        }
        if (writing) {
            writing = false
            loop.modify(fd, EPOLLIN)
        }
        if (closeWhenWritten) close()
    }

    private companion object {
        const val READ_CHUNK = 16 * 1024
        const val READS_PER_TURN = 16
        const val PUBSUB_HARD_LIMIT = 32L * 1024 * 1024
        const val PUBSUB_SOFT_LIMIT = 8L * 1024 * 1024
        val PUBSUB_SOFT_SECONDS = 60.seconds
    }
}
