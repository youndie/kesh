package io.github.youndie.kesh.server.http

import io.github.youndie.kesh.server.BuildInfo
import io.github.youndie.kesh.server.info.CommandStats
import io.github.youndie.kesh.server.info.ProcessFacts

/**
 * `/metrics` in the Prometheus text format, version 0.0.4. The store's numbers are copied on the
 * store thread into [Store] and rendered off it; the process's own — resident memory, threads — are
 * read from the kernel at render time, so a scrape during the snapshot load still answers them.
 *
 * Resident memory and `used_memory` stand side by side, with the thread count, because their ratio
 * is what research R-2 watches (B-11 measured 2.4–2.8).
 */
object Metrics {
    /** The store's side, copied on the store thread. */
    class Store(
        val usedMemory: Long,
        val maxMemory: Long,
        val keys: Long,
        val expires: Long,
        val expiredKeys: Long,
        val evictedKeys: Long,
        val connectedClients: Long,
        val connectionsReceived: Long,
        val rejectedConnections: Long,
        val pubsubChannels: Long,
        val pubsubPatterns: Long,
        val commands: List<Pair<String, CommandStats.Command>>,
    )

    const val CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8"

    /** The exposition; [store] is `null` until the server has started. */
    fun render(store: Store?): String =
        buildString {
            gauge("kesh_resident_memory_bytes", "Resident memory of the process (VmRSS).", ProcessFacts.residentBytes())
            gauge("kesh_threads", "Threads of the process.", ProcessFacts.threads())
            // What the binary was built with (B-30): a measured process says which arm it is.
            append("# HELP kesh_build_info How this binary was built; the value is always 1.\n")
            append("# TYPE kesh_build_info gauge\n")
            val pageSize = BuildInfo.ALLOCATOR_PAGE_SIZE_KB
            append("kesh_build_info{allocator_page_size_kb=\"$pageSize\"} 1\n")
            if (store == null) return@buildString
            gauge("kesh_used_memory_bytes", "The dataset as kesh accounts for it: INFO used_memory.", store.usedMemory)
            gauge("kesh_maxmemory_bytes", "maxmemory; 0 for no limit.", store.maxMemory)
            gauge("kesh_keys", "Keys in the keyspace.", store.keys)
            gauge("kesh_keys_with_expiry", "Keys that have an expiry.", store.expires)
            counter("kesh_expired_keys_total", "Keys deleted because their time had passed.", store.expiredKeys)
            counter("kesh_evicted_keys_total", "Keys evicted by maxmemory-policy.", store.evictedKeys)
            gauge("kesh_connected_clients", "RESP connections open now.", store.connectedClients)
            counter("kesh_connections_received_total", "RESP connections accepted.", store.connectionsReceived)
            counter(
                "kesh_rejected_connections_total",
                "RESP connections refused at maxclients.",
                store.rejectedConnections,
            )
            gauge("kesh_pubsub_channels", "Channels with at least one subscriber.", store.pubsubChannels)
            gauge("kesh_pubsub_patterns", "Patterns with at least one subscriber.", store.pubsubPatterns)
            histogram(store.commands)
        }

    private fun StringBuilder.gauge(
        name: String,
        help: String,
        value: Long,
    ) = family(name, help, "gauge", value)

    private fun StringBuilder.counter(
        name: String,
        help: String,
        value: Long,
    ) = family(name, help, "counter", value)

    private fun StringBuilder.family(
        name: String,
        help: String,
        type: String,
        value: Long,
    ) {
        append("# HELP ")
            .append(name)
            .append(' ')
            .append(help)
            .append('\n')
        append("# TYPE ")
            .append(name)
            .append(' ')
            .append(type)
            .append('\n')
        append(name).append(' ').append(value).append('\n')
    }

    private fun StringBuilder.histogram(commands: List<Pair<String, CommandStats.Command>>) {
        val name = "kesh_command_duration_seconds"
        append("# HELP ").append(name).append(" Time each command took on the store thread, by command.\n")
        append("# TYPE ").append(name).append(" histogram\n")
        for ((command, stats) in commands) {
            var cumulative = 0L
            for (i in CommandStats.BOUNDS.indices) {
                cumulative += stats.buckets[i]
                append(name).append("_bucket{command=\"").append(command).append("\",le=\"")
                append(seconds(CommandStats.BOUNDS[i])).append("\"} ").append(cumulative).append('\n')
            }
            cumulative += stats.buckets[CommandStats.BOUNDS.size]
            append(name).append("_bucket{command=\"").append(command).append("\",le=\"+Inf\"} ")
            append(cumulative).append('\n')
            append(name).append("_sum{command=\"").append(command).append("\"} ")
            append(seconds(stats.totalMicros)).append('\n')
            append(name)
                .append("_count{command=\"")
                .append(command)
                .append("\"} ")
                .append(stats.count)
                .append('\n')
        }
    }

    /** Microseconds as decimal seconds, exactly: 50 → `0.00005`. */
    private fun seconds(micros: Long): String {
        val whole = micros / 1_000_000
        val fraction = (micros % 1_000_000).toString().padStart(6, '0').trimEnd('0')
        return if (fraction.isEmpty()) "$whole" else "$whole.$fraction"
    }
}
