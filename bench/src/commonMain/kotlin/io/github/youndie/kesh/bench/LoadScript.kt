package io.github.youndie.kesh.bench

import io.github.youndie.kesh.resp.Reply
import io.github.youndie.kesh.resp.ReplyWriter

/** Where the script's bytes go: a file, stdout, a digest. */
fun interface ByteSink {
    fun write(
        bytes: ByteArray,
        length: Int,
    )
}

/**
 * Writes entries as RESP commands — what `redis-cli --pipe` and kesh's own loader read.
 *
 * `SET key value [EX ttl]`, `HSET key f v …`, `RPUSH key item …`, `SADD key member …`, and
 * `ZADD key score member …` in chunks of [ZADD_CHUNK] pairs, so no single command is 100 000 members
 * long. A command is a RESP array of bulk strings, which is exactly what `resp`'s writer produces for
 * a [Reply.Multi] of [Reply.Bulk]; the bytes are flushed to the [sink] in [flushAt]-sized pieces.
 */
class LoadScript(
    private val sink: ByteSink,
    private val flushAt: Int = 1 shl 20,
) {
    private val writer = ReplyWriter(flushAt + (1 shl 16))

    fun write(entry: Entry) {
        when (entry) {
            is StringEntry -> {
                val args = mutableListOf("SET".bytes(), entry.key, entry.value)
                entry.ttlSeconds?.let {
                    args += "EX".bytes()
                    args += it.toString().bytes()
                }
                command(args)
            }

            is HashEntry -> {
                command(listOf("HSET".bytes(), entry.key) + entry.fields.flatMap { listOf(it.first, it.second) })
            }

            is ListEntry -> {
                command(listOf("RPUSH".bytes(), entry.key) + entry.items)
            }

            is SetEntry -> {
                command(listOf("SADD".bytes(), entry.key) + entry.members)
            }

            is SortedSetEntry -> {
                entry.members.indices.chunked(ZADD_CHUNK).forEach { chunk ->
                    command(
                        listOf("ZADD".bytes(), entry.key) +
                            chunk.flatMap { listOf(entry.scores[it].toString().bytes(), entry.members[it]) },
                    )
                }
            }
        }
    }

    fun finish() = flush()

    private fun command(args: List<ByteArray>) {
        writer.write(Reply.Multi(args.map { Reply.Bulk(it) }))
        if (writer.length >= flushAt) flush()
    }

    private fun flush() {
        if (writer.length == 0) return
        sink.write(writer.toByteArray(), writer.length)
        writer.clear()
    }

    private fun String.bytes() = encodeToByteArray()

    companion object {
        const val ZADD_CHUNK = 1000
    }
}
