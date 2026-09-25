package io.github.youndie.kesh.bench

import io.github.youndie.kesh.store.Db
import io.github.youndie.kesh.store.commands.StoreCommands

/**
 * The reference dataset put into a [Db] through the store's own commands — the same `SET … EX`,
 * `HSET`, `RPUSH`, `SADD`, `ZADD` that [LoadScript] writes for the wire, without the wire.
 */
object DatasetLoader {
    private val commands = StoreCommands.all.associateBy { it.name }

    /** Loads [dataset] into [db]; returns how many keys it wrote and how many had a TTL. */
    fun load(
        dataset: ReferenceDataset,
        db: Db,
    ): Pair<Int, Int> {
        var keys = 0
        var withTtl = 0
        dataset.entries().forEach { entry ->
            keys++
            when (entry) {
                is StringEntry -> {
                    val ttl = entry.ttlSeconds
                    if (ttl != null) withTtl++
                    run(
                        db,
                        listOf(bytes("SET"), entry.key, entry.value) +
                            (ttl?.let { listOf(bytes("EX"), bytes("$it")) } ?: emptyList()),
                    )
                }

                is HashEntry -> {
                    run(db, listOf(bytes("HSET"), entry.key) + entry.fields.flatMap { listOf(it.first, it.second) })
                }

                is ListEntry -> {
                    run(db, listOf(bytes("RPUSH"), entry.key) + entry.items)
                }

                is SetEntry -> {
                    run(db, listOf(bytes("SADD"), entry.key) + entry.members)
                }

                is SortedSetEntry -> {
                    run(
                        db,
                        listOf(bytes("ZADD"), entry.key) +
                            entry.members.indices.flatMap { listOf(bytes("${entry.scores[it]}"), entry.members[it]) },
                    )
                }
            }
        }
        return keys to withTtl
    }

    private fun run(
        db: Db,
        args: List<ByteArray>,
    ) {
        commands.getValue(args[0].decodeToString().lowercase()).run(db, args)
    }

    private fun bytes(text: String) = text.encodeToByteArray()
}
