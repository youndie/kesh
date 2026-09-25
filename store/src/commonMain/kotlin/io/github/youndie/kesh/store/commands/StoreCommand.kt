package io.github.youndie.kesh.store.commands

import io.github.youndie.kesh.resp.Reply
import io.github.youndie.kesh.resp.parseRedisLong
import io.github.youndie.kesh.store.Db
import io.github.youndie.kesh.store.RedisFloat
import io.github.youndie.kesh.store.hashes.HashValue
import io.github.youndie.kesh.store.keyspace.Entry
import io.github.youndie.kesh.store.lists.ListValue
import io.github.youndie.kesh.store.sets.SetValue
import io.github.youndie.kesh.store.zsets.ZSetValue

/**
 * A data command: its name and Redis's arity (positive exact, negative minimum, name included), and
 * what it does to the [Db]. The server's dispatcher checks existence, arity and authentication before
 * calling [handler], in Redis's order; the handler checks everything else, in Redis's order too.
 */
class StoreCommand(
    val name: String,
    val arity: Int,
    val handler: (Db, List<ByteArray>) -> Reply,
) {
    /** Redis's `write` flag: the command may change the dataset, so it is accounted (research D-10). */
    val write: Boolean get() = name in StoreCommands.WRITE

    /** Redis's `denyoom` flag: refused while `used_memory > maxmemory` (B-11). */
    val denyOom: Boolean get() = name in StoreCommands.DENY_OOM

    /** Runs the command, accounting what a write changes — the way to call [handler]. */
    fun run(
        db: Db,
        args: List<ByteArray>,
    ): Reply {
        if (!write) return handler(db, args)
        db.begin()
        try {
            return handler(db, args)
        } finally {
            db.settle()
        }
    }
}

/** The replies and argument readers every command group shares, worded as Redis words them. */
internal object Replies {
    val WRONGTYPE: Reply = Reply.Error("WRONGTYPE Operation against a key holding the wrong kind of value")
    val SYNTAX: Reply = Reply.Error("ERR syntax error")
    val NOT_AN_INTEGER: Reply = Reply.Error("ERR value is not an integer or out of range")
    val NOT_A_FLOAT: Reply = Reply.Error("ERR value is not a valid float")
    val NO_SUCH_KEY: Reply = Reply.Error("ERR no such key")
    val ZERO: Reply = Reply.Integer(0)
    val ONE: Reply = Reply.Integer(1)
    val EMPTY_BULK: Reply = Reply.Bulk(ByteArray(0))

    /** `addReplyErrorExpireTime`: the command's full name, lower case, as Redis prints it. */
    fun invalidExpireTime(command: String): Reply = Reply.Error("ERR invalid expire time in '$command' command")

    fun wrongArity(command: String): Reply = Reply.Error("ERR wrong number of arguments for '$command' command")

    fun bulk(bytes: ByteArray): Reply = Reply.Bulk(bytes)

    fun long(bytes: ByteArray): Long? = parseRedisLong(bytes)

    fun double(bytes: ByteArray): Double? = RedisFloat.parse(bytes.decodeToString())
}

/** A thrown reply: how a helper deep in a command answers with an error without threading results. */
internal class ReplyException(
    val reply: Reply,
) : Exception()

/** The string value at [entry], or a thrown `WRONGTYPE` — Redis's `checkType` for strings. */
internal fun stringOf(entry: Entry?): ByteArray? =
    when (val value = entry?.value) {
        null -> null
        is ByteArray -> value
        else -> throw ReplyException(Replies.WRONGTYPE)
    }

internal inline fun answering(block: () -> Reply): Reply =
    try {
        block()
    } catch (e: ReplyException) {
        e.reply
    }

/** `getObjectTypeName`: what `TYPE` answers and `SCAN … TYPE` filters by. */
internal fun typeNameOf(value: Any?): String =
    when (value) {
        null -> "none"
        is ByteArray -> "string"
        is HashValue -> "hash"
        is ListValue -> "list"
        is SetValue -> "set"
        is ZSetValue -> "zset"
        else -> "unknown"
    }

/** Every data command, group by group — what the server's dispatcher and the store's tests register. */
object StoreCommands {
    /** The commands flagged `write` in `redis/redis@7.2.5!/src/commands.def`, among kesh's. */
    val WRITE: Set<String> =
        setOf(
            "set",
            "setnx",
            "setex",
            "psetex",
            "mset",
            "msetnx",
            "append",
            "setrange",
            "incr",
            "incrby",
            "decr",
            "decrby",
            "incrbyfloat",
            "getset",
            "getdel",
            "getex",
            "del",
            "unlink",
            "expire",
            "pexpire",
            "expireat",
            "pexpireat",
            "persist",
            "rename",
            "renamenx",
            "flushall",
            "flushdb",
            "hset",
            "hdel",
            "hincrby",
            "hincrbyfloat",
            "lpush",
            "rpush",
            "lpop",
            "rpop",
            "lset",
            "ltrim",
            "lrem",
            "sadd",
            "srem",
            "spop",
            "zadd",
            "zincrby",
            "zrem",
            "zremrangebyscore",
            "zremrangebyrank",
            "zpopmin",
            "zpopmax",
        )

    /** The commands flagged `denyoom` there: those that may add data. */
    val DENY_OOM: Set<String> =
        setOf(
            "set",
            "setnx",
            "setex",
            "psetex",
            "mset",
            "msetnx",
            "append",
            "setrange",
            "incr",
            "incrby",
            "decr",
            "decrby",
            "incrbyfloat",
            "getset",
            "hset",
            "hincrby",
            "hincrbyfloat",
            "lpush",
            "rpush",
            "lset",
            "sadd",
            "zadd",
            "zincrby",
        )

    val all: List<StoreCommand> =
        StringCommands.all + KeyCommands.all + HashCommands.all + ListCommands.all + SetCommands.all +
            SortedSetCommands.all + ScanCommands.all
}
