package io.github.youndie.kesh.store.commands

import io.github.youndie.kesh.resp.Reply
import io.github.youndie.kesh.store.Db
import io.github.youndie.kesh.store.RedisFloat
import io.github.youndie.kesh.store.commands.Replies.EMPTY_BULK
import io.github.youndie.kesh.store.commands.Replies.NOT_AN_INTEGER
import io.github.youndie.kesh.store.commands.Replies.NOT_A_FLOAT
import io.github.youndie.kesh.store.commands.Replies.ONE
import io.github.youndie.kesh.store.commands.Replies.SYNTAX
import io.github.youndie.kesh.store.commands.Replies.ZERO
import io.github.youndie.kesh.store.commands.Replies.invalidExpireTime
import io.github.youndie.kesh.store.commands.Replies.long
import io.github.youndie.kesh.store.commands.Replies.wrongArity

/**
 * The string commands (`endpoint-strings`), following `redis/redis@7.2!/src/t_string.c` function by
 * function: the same checks, in the same order, with the same replies.
 */
object StringCommands {
    /** `proto-max-bulk-len`: the longest a string may grow to by `APPEND` or `SETRANGE`. */
    var maxStringLength: Long = 512L * 1024 * 1024

    val all: List<StoreCommand> =
        listOf(
            StoreCommand("get", 2) { db, a -> answering { get(db, a[1]) } },
            StoreCommand("set", -3) { db, a -> answering { set(db, a) } },
            StoreCommand("setnx", 3) { db, a ->
                answering {
                    setGeneric(db, "setnx", a[1], a[2], nx = true, abortReply = ZERO, okReply = ONE)
                }
            },
            StoreCommand("setex", 4) { db, a ->
                answering {
                    setGeneric(db, "setex", a[1], a[3], expire = a[2], unitSeconds = true, relative = true)
                }
            },
            StoreCommand("psetex", 4) { db, a ->
                answering {
                    setGeneric(db, "psetex", a[1], a[3], expire = a[2], unitSeconds = false, relative = true)
                }
            },
            StoreCommand("getset", 3) { db, a -> answering { getset(db, a) } },
            StoreCommand("getdel", 2) { db, a -> answering { getdel(db, a) } },
            StoreCommand("getex", -2) { db, a -> answering { getex(db, a) } },
            StoreCommand("mget", -2) { db, a -> mget(db, a) },
            StoreCommand("mset", -3) { db, a -> mset(db, a, nx = false) },
            StoreCommand("msetnx", -3) { db, a -> mset(db, a, nx = true) },
            StoreCommand("append", 3) { db, a -> answering { append(db, a) } },
            StoreCommand(
                "strlen",
                2,
            ) { db, a -> answering { Reply.Integer((stringOf(db.lookup(a[1]))?.size ?: 0).toLong()) } },
            StoreCommand("incr", 2) { db, a -> answering { incrBy(db, a[1], 1) } },
            StoreCommand("decr", 2) { db, a -> answering { incrBy(db, a[1], -1) } },
            StoreCommand("incrby", 3) { db, a ->
                answering {
                    incrBy(
                        db,
                        a[1],
                        long(a[2]) ?: return@answering NOT_AN_INTEGER,
                    )
                }
            },
            StoreCommand("decrby", 3) { db, a -> answering { decrBy(db, a) } },
            StoreCommand("incrbyfloat", 3) { db, a -> answering { incrByFloat(db, a) } },
            StoreCommand("getrange", 4) { db, a -> answering { getrange(db, a) } },
            StoreCommand("setrange", 4) { db, a -> answering { setrange(db, a) } },
        )

    private fun get(
        db: Db,
        key: ByteArray,
    ): Reply = stringOf(db.lookup(key))?.let { Reply.Bulk(it) } ?: Reply.NULL_BULK

    /** `SET key value [NX|XX] [GET] [EX s|PX ms|EXAT s|PXAT ms|KEEPTTL]` — `parseExtendedStringArgumentsOrReply`. */
    private fun set(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        var nx = false
        var xx = false
        var get = false
        var keepTtl = false
        var expire: ByteArray? = null
        var unitSeconds = true
        var relative = true
        var unit: String? = null
        var j = 3
        while (j < a.size) {
            val option = a[j].decodeToString()
            val hasNext = j + 1 < a.size
            val expiryFree = unit == null
            when {
                option.equals("nx", true) && !xx -> {
                    nx = true
                }

                option.equals("xx", true) && !nx -> {
                    xx = true
                }

                option.equals("get", true) -> {
                    get = true
                }

                option.equals("keepttl", true) && expiryFree -> {
                    keepTtl = true
                }

                option.equals("ex", true) && !keepTtl && (unit == null || unit == "ex") && hasNext -> {
                    unit = "ex"
                    expire = a[++j]
                    unitSeconds = true
                    relative = true
                }

                option.equals("px", true) && !keepTtl && (unit == null || unit == "px") && hasNext -> {
                    unit = "px"
                    expire = a[++j]
                    unitSeconds = false
                    relative = true
                }

                option.equals("exat", true) && !keepTtl && (unit == null || unit == "exat") && hasNext -> {
                    unit = "exat"
                    expire = a[++j]
                    unitSeconds = true
                    relative = false
                }

                option.equals("pxat", true) && !keepTtl && (unit == null || unit == "pxat") && hasNext -> {
                    unit = "pxat"
                    expire = a[++j]
                    unitSeconds = false
                    relative = false
                }

                else -> {
                    return SYNTAX
                }
            }
            j++
        }
        return setGeneric(db, "set", a[1], a[2], nx, xx, get, keepTtl, expire, unitSeconds, relative)
    }

    /** `setGenericCommand`. */
    private fun setGeneric(
        db: Db,
        command: String,
        key: ByteArray,
        value: ByteArray,
        nx: Boolean = false,
        xx: Boolean = false,
        get: Boolean = false,
        keepTtl: Boolean = false,
        expire: ByteArray? = null,
        unitSeconds: Boolean = true,
        relative: Boolean = true,
        okReply: Reply = Reply.OK,
        abortReply: Reply = Reply.NULL_BULK,
    ): Reply {
        val expireAt = expire?.let { expireMillis(db, command, it, unitSeconds, relative) }
        val previous = if (get) get(db, key) else null
        val found = db.lookup(key) != null
        if ((nx && found) || (xx && !found)) return previous ?: abortReply
        val entry = db.set(key, value, keepTtl = keepTtl || expire != null)
        if (expireAt != null) entry.expireAt = expireAt
        return previous ?: okReply
    }

    /** `getExpireMillisecondsOrReply`: positive, not overflowing, and absolute when it is stored. */
    internal fun expireMillis(
        db: Db,
        command: String,
        raw: ByteArray,
        unitSeconds: Boolean,
        relative: Boolean,
    ): Long {
        var ms = long(raw) ?: throw ReplyException(NOT_AN_INTEGER)
        if (ms <= 0 || (unitSeconds && ms > Long.MAX_VALUE / 1000)) throw ReplyException(invalidExpireTime(command))
        if (unitSeconds) ms *= 1000
        if (relative) ms += db.now
        if (ms <= 0) throw ReplyException(invalidExpireTime(command))
        return ms
    }

    private fun getset(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        val previous = get(db, a[1])
        db.set(a[1], a[2])
        return previous
    }

    private fun getdel(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        val previous = get(db, a[1])
        db.delete(a[1])
        return previous
    }

    /** `GETEX key [EX s|PX ms|EXAT s|PXAT ms|PERSIST]`. */
    private fun getex(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        var unit: String? = null
        var expire: ByteArray? = null
        var persist = false
        var j = 2
        while (j < a.size) {
            val option = a[j].decodeToString().lowercase()
            val hasNext = j + 1 < a.size
            when {
                option == "persist" && unit == null -> {
                    persist = true
                }

                option in
                    setOf(
                        "ex",
                        "px",
                        "exat",
                        "pxat",
                    ) && !persist && (unit == null || unit == option) && hasNext -> {
                    unit = option
                    expire = a[++j]
                }

                else -> {
                    return SYNTAX
                }
            }
            j++
        }
        val entry = db.lookup(a[1]) ?: return Reply.NULL_BULK
        val value = stringOf(entry)!!
        if (expire != null) {
            val absolute = unit == "exat" || unit == "pxat"
            val expireAt =
                expireMillis(db, "getex", expire, unitSeconds = unit == "ex" || unit == "exat", relative = !absolute)
            if (absolute && expireAt <= db.now) {
                db.remove(a[1])
            } else {
                entry.expireAt = expireAt
            }
        } else if (persist) {
            entry.expireAt = io.github.youndie.kesh.store.keyspace.Entry.NO_EXPIRY
        }
        return Reply.Bulk(value)
    }

    private fun mget(
        db: Db,
        a: List<ByteArray>,
    ): Reply =
        Reply.Multi(
            (1 until a.size).map { i ->
                (db.lookup(a[i])?.value as? ByteArray)?.let { Reply.Bulk(it) } ?: Reply.NULL_BULK
            },
        )

    private fun mset(
        db: Db,
        a: List<ByteArray>,
        nx: Boolean,
    ): Reply {
        if (a.size % 2 == 0) return wrongArity(if (nx) "msetnx" else "mset")
        if (nx && (1 until a.size step 2).any { db.lookup(a[it]) != null }) return ZERO
        for (i in 1 until a.size step 2) db.set(a[i], a[i + 1])
        return if (nx) ONE else Reply.OK
    }

    private fun append(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        val entry = db.lookup(a[1])
        if (entry == null) {
            db.set(a[1], a[2])
            return Reply.Integer(a[2].size.toLong())
        }
        val current = stringOf(entry)!!
        checkLength(current.size.toLong(), a[2].size.toLong())
        entry.value = current + a[2]
        return Reply.Integer((current.size + a[2].size).toLong())
    }

    /** `incrDecrCommand`: a missing key counts as 0; the expiry is kept. */
    private fun incrBy(
        db: Db,
        key: ByteArray,
        increment: Long,
    ): Reply {
        val entry = db.lookup(key)
        val old = stringOf(entry)?.let { long(it) ?: return NOT_AN_INTEGER } ?: 0L
        if ((increment < 0 && old < 0 && increment < Long.MIN_VALUE - old) ||
            (increment > 0 && old > 0 && increment > Long.MAX_VALUE - old)
        ) {
            return Reply.Error("ERR increment or decrement would overflow")
        }
        val value = old + increment
        val bytes = value.toString().encodeToByteArray()
        if (entry != null) entry.value = bytes else db.set(key, bytes)
        return Reply.Integer(value)
    }

    private fun decrBy(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        val decrement = long(a[2]) ?: return NOT_AN_INTEGER
        if (decrement == Long.MIN_VALUE) return Reply.Error("ERR decrement would overflow")
        return incrBy(db, a[1], -decrement)
    }

    /** `incrbyfloatCommand` — in `Double`, not `long double`: see [RedisFloat]. */
    private fun incrByFloat(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        val entry = db.lookup(a[1])
        val old = stringOf(entry)?.let { RedisFloat.parse(it.decodeToString()) ?: return NOT_A_FLOAT } ?: 0.0
        val increment = RedisFloat.parse(a[2].decodeToString()) ?: return NOT_A_FLOAT
        val value = old + increment
        if (value.isNaN() || value.isInfinite()) return Reply.Error("ERR increment would produce NaN or Infinity")
        val bytes = RedisFloat.format(value).encodeToByteArray()
        if (entry != null) entry.value = bytes else db.set(a[1], bytes)
        return Reply.Bulk(bytes)
    }

    private fun getrange(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        var start = long(a[2]) ?: return NOT_AN_INTEGER
        var end = long(a[3]) ?: return NOT_AN_INTEGER
        val value = stringOf(db.lookup(a[1])) ?: return EMPTY_BULK
        val length = value.size.toLong()
        if (start < 0 && end < 0 && start > end) return EMPTY_BULK
        if (start < 0) start += length
        if (end < 0) end += length
        if (start < 0) start = 0
        if (end < 0) end = 0
        if (end >= length) end = length - 1
        if (start > end || length == 0L) return EMPTY_BULK
        return Reply.Bulk(value.copyOfRange(start.toInt(), end.toInt() + 1))
    }

    private fun setrange(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        val offset = long(a[2]) ?: return NOT_AN_INTEGER
        if (offset < 0) return Reply.Error("ERR offset is out of range")
        val patch = a[3]
        val entry = db.lookup(a[1])
        if (entry == null) {
            if (patch.isEmpty()) return ZERO
            checkLength(offset, patch.size.toLong())
            val value = ByteArray((offset + patch.size).toInt())
            patch.copyInto(value, offset.toInt())
            db.set(a[1], value)
            return Reply.Integer(value.size.toLong())
        }
        val current = stringOf(entry)!!
        if (patch.isEmpty()) return Reply.Integer(current.size.toLong())
        checkLength(offset, patch.size.toLong())
        val value =
            if (offset + patch.size >
                current.size
            ) {
                current.copyOf((offset + patch.size).toInt())
            } else {
                current.copyOf()
            }
        patch.copyInto(value, offset.toInt())
        entry.value = value
        return Reply.Integer(value.size.toLong())
    }

    /** `checkStringLength`. */
    private fun checkLength(
        size: Long,
        append: Long,
    ) {
        val total = size + append
        if (total > maxStringLength || total < size || total < append) {
            throw ReplyException(Reply.Error("ERR string exceeds maximum allowed size (proto-max-bulk-len)"))
        }
    }
}
