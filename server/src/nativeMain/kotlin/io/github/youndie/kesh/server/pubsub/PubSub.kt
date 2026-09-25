package io.github.youndie.kesh.server.pubsub

import io.github.youndie.kesh.resp.Reply
import io.github.youndie.kesh.server.client.ClientState
import io.github.youndie.kesh.store.Glob

/**
 * Pub/Sub (B-27, research D-26) as `redis/redis@7.2!/src/pubsub.c` has it: channels and patterns,
 * each to its subscribers in the order they subscribed, and every client's own channels and patterns
 * — whose total is the count each confirmation carries. On the store thread, with the clients it
 * delivers to (research D-14), so a `PUBLISH` counts its receivers exactly and delivers in order.
 *
 * `PUBLISH` goes to a channel's subscribers first, then to each pattern that matches it
 * (`stringmatchlen`, [Glob]), in this registry's order of patterns — Redis's is its dictionary's,
 * which is no order a client can rely on.
 */
class PubSub {
    /** A channel or pattern: bytes compared by content. */
    class Name(
        val bytes: ByteArray,
    ) {
        private val hash = bytes.contentHashCode()

        override fun equals(other: Any?): Boolean = other is Name && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = hash
    }

    private val channels = HashMap<Name, LinkedHashSet<ClientState>>()
    private val patterns = LinkedHashMap<Name, LinkedHashSet<ClientState>>()

    /** `pubsub_channels`: channels with at least one subscriber. */
    val channelCount: Int get() = channels.size

    /** `pubsub_patterns`: patterns with at least one subscriber. */
    val patternCount: Int get() = patterns.size

    /** `SUBSCRIBE`: one confirmation per channel, subscribed already or not. */
    fun subscribe(
        client: ClientState,
        names: List<ByteArray>,
    ): Reply =
        Reply.Frames(
            names.map { bytes ->
                val name = Name(bytes)
                if (client.channels.add(name)) channels.getOrPut(name) { LinkedHashSet() } += client
                confirmation(SUBSCRIBE, bytes, client)
            },
        )

    /** `UNSUBSCRIBE`: the channels named, or all of the client's — one confirmation each, or one with a null channel for none. */
    fun unsubscribe(
        client: ClientState,
        names: List<ByteArray>,
    ): Reply = leave(client, names, client.channels, channels, UNSUBSCRIBE)

    /** `PSUBSCRIBE`: one confirmation per pattern. */
    fun psubscribe(
        client: ClientState,
        names: List<ByteArray>,
    ): Reply =
        Reply.Frames(
            names.map { bytes ->
                val name = Name(bytes)
                if (client.patterns.add(name)) patterns.getOrPut(name) { LinkedHashSet() } += client
                confirmation(PSUBSCRIBE, bytes, client)
            },
        )

    /** `PUNSUBSCRIBE`, as [unsubscribe] for patterns. */
    fun punsubscribe(
        client: ClientState,
        names: List<ByteArray>,
    ): Reply = leave(client, names, client.patterns, patterns, PUNSUBSCRIBE)

    /** `PUBLISH`: the message to every subscriber of [channel] and of every pattern matching it; how many received it. */
    fun publish(
        channel: ByteArray,
        message: ByteArray,
    ): Int {
        var receivers = 0
        // Copies: a delivery can close a subscriber past its output limit, which removes it here.
        channels[Name(channel)]?.toList()?.let { subscribers ->
            val frame = Reply.Multi(listOf(MESSAGE, Reply.Bulk(channel), Reply.Bulk(message)))
            for (client in subscribers) {
                client.deliver?.invoke(frame)
                receivers++
            }
        }
        for ((pattern, subscribers) in patterns.entries.toList()) {
            if (!Glob.matches(pattern.bytes, channel)) continue
            val frame =
                Reply.Multi(
                    listOf(PMESSAGE, Reply.Bulk(pattern.bytes), Reply.Bulk(channel), Reply.Bulk(message)),
                )
            for (client in subscribers.toList()) {
                client.deliver?.invoke(frame)
                receivers++
            }
        }
        return receivers
    }

    /** Everything [client] was subscribed to, when it goes. */
    fun remove(client: ClientState) {
        for (name in client.channels) drop(channels, name, client)
        for (name in client.patterns) drop(patterns, name, client)
        client.channels.clear()
        client.patterns.clear()
    }

    private fun leave(
        client: ClientState,
        names: List<ByteArray>,
        own: LinkedHashSet<Name>,
        registry: MutableMap<Name, LinkedHashSet<ClientState>>,
        kind: Reply,
    ): Reply {
        val targets = if (names.isEmpty()) own.map { it.bytes } else names
        if (targets.isEmpty()) return Reply.Frames(listOf(confirmation(kind, null, client)))
        return Reply.Frames(
            targets.map { bytes ->
                val name = Name(bytes)
                if (own.remove(name)) drop(registry, name, client)
                confirmation(kind, bytes, client)
            },
        )
    }

    private fun drop(
        registry: MutableMap<Name, LinkedHashSet<ClientState>>,
        name: Name,
        client: ClientState,
    ) {
        val subscribers = registry[name] ?: return
        subscribers.remove(client)
        if (subscribers.isEmpty()) registry.remove(name)
    }

    private fun confirmation(
        kind: Reply,
        name: ByteArray?,
        client: ClientState,
    ): Reply = Reply.Multi(listOf(kind, Reply.Bulk(name), Reply.Integer(client.subscriptions.toLong())))

    private companion object {
        val SUBSCRIBE = Reply.Bulk("subscribe".encodeToByteArray())
        val UNSUBSCRIBE = Reply.Bulk("unsubscribe".encodeToByteArray())
        val PSUBSCRIBE = Reply.Bulk("psubscribe".encodeToByteArray())
        val PUNSUBSCRIBE = Reply.Bulk("punsubscribe".encodeToByteArray())
        val MESSAGE = Reply.Bulk("message".encodeToByteArray())
        val PMESSAGE = Reply.Bulk("pmessage".encodeToByteArray())
    }
}
