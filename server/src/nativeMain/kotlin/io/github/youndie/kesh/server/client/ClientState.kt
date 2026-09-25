package io.github.youndie.kesh.server.client

import io.github.youndie.kesh.resp.Reply
import io.github.youndie.kesh.server.pubsub.PubSub
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * One connection as the commands see it. Read and written on the store thread only (research D-14),
 * except [authenticated], which the connection reads between hand-offs to choose its parsing limits.
 */
class ClientState(
    val id: Long,
    val address: String,
    val localAddress: String,
    private val closeSocket: () -> Unit,
    authenticated: Boolean,
) {
    private val createdAt: TimeMark = TimeSource.Monotonic.markNow()
    private var lastInteraction: TimeMark = createdAt

    var authenticated: Boolean = authenticated
        internal set
    var name: String? = null
    var libName: String? = null
    var libVersion: String? = null
    var lastCommand: String = "NULL"
        private set

    /** Reply to what was asked, then close: `QUIT`, or `CLIENT KILL` aimed at itself. */
    var closeAfterReply: Boolean = false

    /** The channels and patterns this client is subscribed to (B-27); the registry is [PubSub]. */
    internal val channels = LinkedHashSet<PubSub.Name>()
    internal val patterns = LinkedHashSet<PubSub.Name>()

    /** Channels and patterns: what a confirmation counts, and what puts the client in subscribe mode. */
    val subscriptions: Int get() = channels.size + patterns.size

    /** Where a published message goes: the connection's output, set by the transport. */
    var deliver: ((Reply) -> Unit)? = null

    /** Closed by another client's `CLIENT KILL`. */
    var killed: Boolean = false
        private set

    val ageSeconds: Long get() = createdAt.elapsedNow().inWholeSeconds
    val idleSeconds: Long get() = lastInteraction.elapsedNow().inWholeSeconds

    fun startCommand(fullName: String) {
        lastCommand = fullName
        lastInteraction = TimeSource.Monotonic.markNow()
    }

    fun kill() {
        killed = true
        closeSocket()
    }
}
