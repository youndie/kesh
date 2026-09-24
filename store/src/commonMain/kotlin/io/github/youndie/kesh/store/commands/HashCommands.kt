package io.github.youndie.kesh.store.commands

import io.github.youndie.kesh.resp.Reply
import io.github.youndie.kesh.store.Db
import io.github.youndie.kesh.store.RedisFloat
import io.github.youndie.kesh.store.commands.Replies.NOT_AN_INTEGER
import io.github.youndie.kesh.store.commands.Replies.NOT_A_FLOAT
import io.github.youndie.kesh.store.commands.Replies.ONE
import io.github.youndie.kesh.store.commands.Replies.WRONGTYPE
import io.github.youndie.kesh.store.commands.Replies.ZERO
import io.github.youndie.kesh.store.commands.Replies.double
import io.github.youndie.kesh.store.commands.Replies.long
import io.github.youndie.kesh.store.commands.Replies.wrongArity
import io.github.youndie.kesh.store.hashes.HashValue
import io.github.youndie.kesh.store.keyspace.Entry

/**
 * The hash commands (`endpoint-hashes`) the brief lists, following `redis/redis@7.2!/src/t_hash.c`
 * function by function. `HSCAN` is B-10's.
 */
object HashCommands {
    private val EMPTY_ARRAY: Reply = Reply.Multi(emptyList())

    val all: List<StoreCommand> =
        listOf(
            StoreCommand("hset", -4) { db, a -> answering { hset(db, a) } },
            StoreCommand("hget", 3) { db, a ->
                answering { hashOf(db.lookup(a[1]))?.get(a[2])?.let { Reply.Bulk(it) } ?: Reply.NULL_BULK }
            },
            StoreCommand("hmget", -3) { db, a ->
                answering {
                    val hash = hashOf(db.lookup(a[1]))
                    Reply.Multi(
                        (2 until a.size).map { i ->
                            hash?.get(a[i])?.let { Reply.Bulk(it) } ?: Reply.NULL_BULK
                        },
                    )
                }
            },
            StoreCommand("hdel", -3) { db, a -> answering { hdel(db, a) } },
            StoreCommand(
                "hlen",
                2,
            ) { db, a -> answering { Reply.Integer((hashOf(db.lookup(a[1]))?.size ?: 0).toLong()) } },
            StoreCommand("hexists", 3) { db, a ->
                answering { if (hashOf(db.lookup(a[1]))?.contains(a[2]) == true) ONE else ZERO }
            },
            StoreCommand("hincrby", 4) { db, a -> answering { hincrby(db, a) } },
            StoreCommand("hincrbyfloat", 4) { db, a -> answering { hincrbyfloat(db, a) } },
            StoreCommand("hgetall", 2) { db, a -> answering { all(db, a[1], fields = true, values = true) } },
            StoreCommand("hkeys", 2) { db, a -> answering { all(db, a[1], fields = true, values = false) } },
            StoreCommand("hvals", 2) { db, a -> answering { all(db, a[1], fields = false, values = true) } },
        )

    /** `hsetCommand`: the pair count is checked before the key is looked at. */
    private fun hset(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        if (a.size % 2 == 1) return wrongArity("hset")
        val hash = lookupOrCreate(db, a[1])
        hash.prepareFor(a, 2)
        var created = 0L
        for (i in 2 until a.size step 2) if (hash.set(a[i], a[i + 1])) created++
        return Reply.Integer(created)
    }

    /** `hdelCommand`: the key goes with its last field, and the loop stops there. */
    private fun hdel(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        val hash = hashOf(db.lookup(a[1])) ?: return ZERO
        var deleted = 0L
        for (i in 2 until a.size) {
            if (hash.delete(a[i])) {
                deleted++
                if (hash.size == 0) {
                    db.delete(a[1])
                    break
                }
            }
        }
        return Reply.Integer(deleted)
    }

    /** `hincrbyCommand`: the increment is read before the key, so a bad one wins over `WRONGTYPE`. */
    private fun hincrby(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        val increment = long(a[3]) ?: return NOT_AN_INTEGER
        val hash = lookupOrCreate(db, a[1])
        val old = hash.get(a[2])?.let { long(it) ?: return Reply.Error("ERR hash value is not an integer") } ?: 0L
        if ((increment < 0 && old < 0 && increment < Long.MIN_VALUE - old) ||
            (increment > 0 && old > 0 && increment > Long.MAX_VALUE - old)
        ) {
            return Reply.Error("ERR increment or decrement would overflow")
        }
        val value = old + increment
        hash.set(a[2], value.toString().encodeToByteArray())
        return Reply.Integer(value)
    }

    /** `hincrbyfloatCommand` — in `Double`, not `long double`, as `INCRBYFLOAT` (see [RedisFloat]). */
    private fun hincrbyfloat(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        val increment = double(a[3]) ?: return NOT_A_FLOAT
        if (increment.isNaN() || increment.isInfinite()) return Reply.Error("ERR value is NaN or Infinity")
        val hash = lookupOrCreate(db, a[1])
        val old = hash.get(a[2])?.let { double(it) ?: return Reply.Error("ERR hash value is not a float") } ?: 0.0
        val value = old + increment
        if (value.isNaN() || value.isInfinite()) return Reply.Error("ERR increment would produce NaN or Infinity")
        val bytes = RedisFloat.format(value).encodeToByteArray()
        hash.set(a[2], bytes)
        return Reply.Bulk(bytes)
    }

    /** `genericHgetallCommand`: pairs, fields or values, in the hash's own order. */
    private fun all(
        db: Db,
        key: ByteArray,
        fields: Boolean,
        values: Boolean,
    ): Reply {
        val hash = hashOf(db.lookup(key)) ?: return EMPTY_ARRAY
        val items = ArrayList<Reply>(hash.size * (if (fields && values) 2 else 1))
        hash.forEach { field, value ->
            if (fields) items.add(Reply.Bulk(field))
            if (values) items.add(Reply.Bulk(value))
        }
        return Reply.Multi(items)
    }

    /** `hashTypeLookupWriteOrCreate`: an absent key becomes an empty packed hash at once. */
    private fun lookupOrCreate(
        db: Db,
        key: ByteArray,
    ): HashValue = hashOf(db.lookup(key)) ?: HashValue(db.seed).also { db.set(key, it) }
}

/** The hash at [entry], or a thrown `WRONGTYPE` — Redis's `checkType` for hashes. */
internal fun hashOf(entry: Entry?): HashValue? =
    when (val value = entry?.value) {
        null -> null
        is HashValue -> value
        else -> throw ReplyException(WRONGTYPE)
    }
