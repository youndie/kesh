package io.github.youndie.kesh.store.commands

import io.github.youndie.kesh.resp.Reply
import io.github.youndie.kesh.store.Db
import io.github.youndie.kesh.store.Glob
import io.github.youndie.kesh.store.commands.Replies.NOT_AN_INTEGER
import io.github.youndie.kesh.store.commands.Replies.NO_SUCH_KEY
import io.github.youndie.kesh.store.commands.Replies.ONE
import io.github.youndie.kesh.store.commands.Replies.SYNTAX
import io.github.youndie.kesh.store.commands.Replies.ZERO
import io.github.youndie.kesh.store.commands.Replies.invalidExpireTime
import io.github.youndie.kesh.store.commands.Replies.long
import io.github.youndie.kesh.store.keyspace.Entry
import kotlin.random.Random

/**
 * The keyspace commands (`endpoint-keyspace`) except `SCAN` (B-10), following
 * `redis/redis@7.2!/src/db.c` and `redis/redis@7.2!/src/expire.c`.
 *
 * `DEL`, `UNLINK` and `FLUSHALL`/`FLUSHDB` with or without `ASYNC` are the same operation (research
 * D-15): under a tracing collector nothing is freed synchronously, so there is no difference to make.
 */
object KeyCommands {
    /** Where `RANDOMKEY` draws from; a field so a test can make it repeatable. */
    var random: Random = Random.Default

    val all: List<StoreCommand> =
        listOf(
            StoreCommand("del", -2) { db, a -> delete(db, a) },
            StoreCommand("unlink", -2) { db, a -> delete(db, a) },
            StoreCommand("exists", -2) { db, a -> Reply.Integer((1 until a.size).count { db.lookup(a[it]) != null }.toLong()) },
            StoreCommand("type", 2) { db, a -> Reply.Simple(typeName(db.lookup(a[1])?.value)) },
            StoreCommand("expire", -3) { db, a -> answering { expire(db, a, "expire", unitSeconds = true, relative = true) } },
            StoreCommand("pexpire", -3) { db, a -> answering { expire(db, a, "pexpire", unitSeconds = false, relative = true) } },
            StoreCommand("expireat", -3) { db, a -> answering { expire(db, a, "expireat", unitSeconds = true, relative = false) } },
            StoreCommand("pexpireat", -3) { db, a -> answering { expire(db, a, "pexpireat", unitSeconds = false, relative = false) } },
            StoreCommand("ttl", 2) { db, a -> ttl(db, a[1], millis = false, absolute = false) },
            StoreCommand("pttl", 2) { db, a -> ttl(db, a[1], millis = true, absolute = false) },
            StoreCommand("expiretime", 2) { db, a -> ttl(db, a[1], millis = false, absolute = true) },
            StoreCommand("pexpiretime", 2) { db, a -> ttl(db, a[1], millis = true, absolute = true) },
            StoreCommand("persist", 2) { db, a -> persist(db, a[1]) },
            StoreCommand("rename", 3) { db, a -> rename(db, a, nx = false) },
            StoreCommand("renamenx", 3) { db, a -> rename(db, a, nx = true) },
            StoreCommand("keys", 2) { db, a -> keys(db, a[1]) },
            StoreCommand("randomkey", 1) { db, _ -> randomKey(db) },
            StoreCommand("dbsize", 1) { db, _ -> Reply.Integer(db.size.toLong()) },
            StoreCommand("flushall", -1) { db, a -> flush(db, a) },
            StoreCommand("flushdb", -1) { db, a -> flush(db, a) },
        )

    private fun delete(
        db: Db,
        a: List<ByteArray>,
    ): Reply = Reply.Integer((1 until a.size).count { db.delete(a[it]) }.toLong())

    /** `getObjectTypeName`. The other four types arrive with B-06 to B-09. */
    private fun typeName(value: Any?): String =
        when (value) {
            null -> "none"
            is ByteArray -> "string"
            else -> "unknown"
        }

    /** `expireGenericCommand` with `parseExtendedExpireArgumentsOrReply`. */
    private fun expire(
        db: Db,
        a: List<ByteArray>,
        command: String,
        unitSeconds: Boolean,
        relative: Boolean,
    ): Reply {
        var nx = false
        var xx = false
        var gt = false
        var lt = false
        for (j in 3 until a.size) {
            when (a[j].decodeToString().lowercase()) {
                "nx" -> nx = true
                "xx" -> xx = true
                "gt" -> gt = true
                "lt" -> lt = true
                else -> return Reply.Error("ERR Unsupported option ${a[j].decodeToString()}")
            }
        }
        if ((nx && xx) || (nx && gt) || (nx && lt)) {
            return Reply.Error("ERR NX and XX, GT or LT options at the same time are not compatible")
        }
        if (gt && lt) return Reply.Error("ERR GT and LT options at the same time are not compatible")

        var `when` = long(a[2]) ?: return NOT_AN_INTEGER
        if (unitSeconds) {
            if (`when` > Long.MAX_VALUE / 1000 || `when` < Long.MIN_VALUE / 1000) return invalidExpireTime(command)
            `when` *= 1000
        }
        val base = if (relative) db.now else 0L
        if (`when` > Long.MAX_VALUE - base) return invalidExpireTime(command)
        `when` += base

        val entry = db.lookup(a[1]) ?: return ZERO
        val current = entry.expireAt
        if (nx && current != Entry.NO_EXPIRY) return ZERO
        if (xx && current == Entry.NO_EXPIRY) return ZERO
        if (gt && (`when` <= current || current == Entry.NO_EXPIRY)) return ZERO
        if (lt && current != Entry.NO_EXPIRY && `when` >= current) return ZERO

        // `checkAlreadyExpired`: a time at or before now deletes the key, and still answers 1.
        if (`when` <= db.now) db.keyspace.remove(a[1]) else entry.expireAt = `when`
        return ONE
    }

    /** `ttlGenericCommand`: -2 no key, -1 no expiry, seconds rounded as `(ms + 500) / 1000`. */
    private fun ttl(
        db: Db,
        key: ByteArray,
        millis: Boolean,
        absolute: Boolean,
    ): Reply {
        val entry = db.lookup(key) ?: return Reply.Integer(-2)
        if (entry.expireAt == Entry.NO_EXPIRY) return Reply.Integer(-1)
        val value = (if (absolute) entry.expireAt else entry.expireAt - db.now).coerceAtLeast(0)
        return Reply.Integer(if (millis) value else (value + 500) / 1000)
    }

    private fun persist(
        db: Db,
        key: ByteArray,
    ): Reply {
        val entry = db.lookup(key) ?: return ZERO
        if (entry.expireAt == Entry.NO_EXPIRY) return ZERO
        entry.expireAt = Entry.NO_EXPIRY
        return ONE
    }

    /** `renameGenericCommand`: the expiry moves with the value; renaming a key to itself is a no-op. */
    private fun rename(
        db: Db,
        a: List<ByteArray>,
        nx: Boolean,
    ): Reply {
        val source = db.lookup(a[1]) ?: return NO_SUCH_KEY
        if (a[1].contentEquals(a[2])) return if (nx) ZERO else Reply.OK
        if (db.lookup(a[2]) != null) {
            if (nx) return ZERO
            db.keyspace.remove(a[2])
        }
        db.keyspace.remove(a[1])
        val target = db.keyspace.put(a[2], source.value)
        target.expireAt = source.expireAt
        return if (nx) ONE else Reply.OK
    }

    /** `keysCommand`: every live key the pattern matches, in table order — no order is promised. */
    private fun keys(
        db: Db,
        pattern: ByteArray,
    ): Reply {
        val all = pattern.size == 1 && pattern[0] == '*'.code.toByte()
        val found = ArrayList<Reply>()
        db.keyspace.forEach { entry ->
            if ((all || Glob.matches(pattern, entry.key)) && !db.isExpired(entry)) found += Reply.Bulk(entry.key)
        }
        return Reply.Multi(found)
    }

    /** `dbRandomKey`: an expired key drawn is deleted and another drawn, as Redis does. */
    private fun randomKey(db: Db): Reply {
        while (true) {
            val entry = db.keyspace.randomEntry { random.nextInt(it) } ?: return Reply.NULL_BULK
            if (!db.isExpired(entry)) return Reply.Bulk(entry.key)
            db.keyspace.remove(entry.key)
        }
    }

    /** `getFlushCommandFlags`: no argument, `SYNC` or `ASYNC`, all the same here (research D-15). */
    private fun flush(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        val ok =
            a.size == 1 ||
                (a.size == 2 && a[1].decodeToString().lowercase().let { it == "sync" || it == "async" })
        if (!ok) return SYNTAX
        db.clear()
        return Reply.OK
    }
}
