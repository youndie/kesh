package io.github.youndie.kesh.store.commands

import io.github.youndie.kesh.resp.Reply
import io.github.youndie.kesh.store.Db
import io.github.youndie.kesh.store.commands.Replies.NOT_AN_INTEGER
import io.github.youndie.kesh.store.commands.Replies.NO_SUCH_KEY
import io.github.youndie.kesh.store.commands.Replies.WRONGTYPE
import io.github.youndie.kesh.store.commands.Replies.ZERO
import io.github.youndie.kesh.store.commands.Replies.long
import io.github.youndie.kesh.store.commands.Replies.wrongArity
import io.github.youndie.kesh.store.keyspace.Entry
import io.github.youndie.kesh.store.lists.ListValue

/**
 * The list commands (`endpoint-lists`) the brief lists, following `redis/redis@7.2!/src/t_list.c`
 * function by function: the same checks in the same order — some read their numbers before the key,
 * some after — with the same replies.
 */
object ListCommands {
    private val EMPTY_ARRAY: Reply = Reply.Multi(emptyList())
    private val NULL_ARRAY: Reply = Reply.Multi(null)

    /** `getPositiveLongFromObjectOrReply`: one message for a count that is not a number and for one below zero. */
    private val MUST_BE_POSITIVE: Reply = Reply.Error("ERR value is out of range, must be positive")
    private val INDEX_OUT_OF_RANGE: Reply = Reply.Error("ERR index out of range")

    val all: List<StoreCommand> =
        listOf(
            StoreCommand("lpush", -3) { db, a -> answering { push(db, a, head = true) } },
            StoreCommand("rpush", -3) { db, a -> answering { push(db, a, head = false) } },
            StoreCommand("lpop", -2) { db, a -> answering { pop(db, a, head = true) } },
            StoreCommand("rpop", -2) { db, a -> answering { pop(db, a, head = false) } },
            StoreCommand(
                "llen",
                2,
            ) { db, a -> answering { Reply.Integer((listValueOf(db.lookup(a[1]))?.size ?: 0).toLong()) } },
            StoreCommand("lindex", 3) { db, a -> answering { lindex(db, a) } },
            StoreCommand("lset", 4) { db, a -> answering { lset(db, a) } },
            StoreCommand("lrange", 4) { db, a -> answering { lrange(db, a) } },
            StoreCommand("ltrim", 4) { db, a -> answering { ltrim(db, a) } },
            StoreCommand("lrem", 4) { db, a -> answering { lrem(db, a) } },
        )

    /** `pushGenericCommand`: items go in one at a time, so `LPUSH k a b c` reads `c b a`. */
    private fun push(
        db: Db,
        a: List<ByteArray>,
        head: Boolean,
    ): Reply {
        val list = listValueOf(db.lookup(a[1])) ?: ListValue().also { db.set(a[1], it) }
        for (i in 2 until a.size) if (head) list.pushFirst(a[i]) else list.pushLast(a[i])
        return Reply.Integer(list.size.toLong())
    }

    /** `popGenericCommand`: the count is read before the key; no count and a count differ in reply. */
    private fun pop(
        db: Db,
        a: List<ByteArray>,
        head: Boolean,
    ): Reply {
        if (a.size > 3) return wrongArity(if (head) "lpop" else "rpop")
        val hasCount = a.size == 3
        val count = if (hasCount) long(a[2])?.takeIf { it >= 0 } ?: return MUST_BE_POSITIVE else 0L
        val list = listValueOf(db.lookup(a[1])) ?: return if (hasCount) NULL_ARRAY else Reply.NULL_BULK
        if (hasCount && count == 0L) return EMPTY_ARRAY
        if (!hasCount) {
            val index = if (head) 0 else list.size - 1
            val item = list.get(index)
            if (head) list.removeFirst(1) else list.removeLast(1)
            removeIfEmpty(db, a[1], list)
            return Reply.Bulk(item)
        }
        val taken = minOf(count, list.size.toLong()).toInt()
        val items =
            if (head) {
                list.range(0, taken - 1).also { list.removeFirst(taken) }
            } else {
                list.range(list.size - taken, list.size - 1).asReversed().also { list.removeLast(taken) }
            }
        removeIfEmpty(db, a[1], list)
        return Reply.Multi(items.map { Reply.Bulk(it) })
    }

    /** `lindexCommand`: the key before the index, so a missing key answers null to any index. */
    private fun lindex(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        val list = listValueOf(db.lookup(a[1])) ?: return Reply.NULL_BULK
        val index = normalise(long(a[2]) ?: return NOT_AN_INTEGER, list) ?: return Reply.NULL_BULK
        return Reply.Bulk(list.get(index))
    }

    /** `lsetCommand`: a missing key is an error here, and before the index is read. */
    private fun lset(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        val list = listValueOf(db.lookup(a[1])) ?: return NO_SUCH_KEY
        val index = normalise(long(a[2]) ?: return NOT_AN_INTEGER, list) ?: return INDEX_OUT_OF_RANGE
        list.set(index, a[3])
        return Reply.OK
    }

    /** `lrangeCommand` and `addListRangeReply`. */
    private fun lrange(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        val start = long(a[2]) ?: return NOT_AN_INTEGER
        val end = long(a[3]) ?: return NOT_AN_INTEGER
        val list = listValueOf(db.lookup(a[1])) ?: return EMPTY_ARRAY
        val (from, to) = clamp(start, end, list.size.toLong()) ?: return EMPTY_ARRAY
        return Reply.Multi(list.range(from, to).map { Reply.Bulk(it) })
    }

    /** `ltrimCommand`: a range that selects nothing empties the list, and so deletes the key. */
    private fun ltrim(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        val start = long(a[2]) ?: return NOT_AN_INTEGER
        val end = long(a[3]) ?: return NOT_AN_INTEGER
        val list = listValueOf(db.lookup(a[1])) ?: return Reply.OK
        val size = list.size
        val kept = clamp(start, end, size.toLong())
        if (kept == null) {
            list.removeFirst(size)
        } else {
            list.removeFirst(kept.first)
            list.removeLast(size - kept.second - 1)
        }
        removeIfEmpty(db, a[1], list)
        return Reply.OK
    }

    /** `lremCommand`: a negative count removes from the tail; zero removes every match. */
    private fun lrem(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        val count = long(a[2]) ?: return NOT_AN_INTEGER
        val list = listValueOf(db.lookup(a[1])) ?: return ZERO
        val removed = list.removeMatching(a[3], if (count < 0) -count else count, fromTail = count < 0)
        removeIfEmpty(db, a[1], list)
        return Reply.Integer(removed)
    }

    /** A negative index counts from the tail; `null` when it lands outside the list. */
    private fun normalise(
        index: Long,
        list: ListValue,
    ): Int? {
        val i = if (index < 0) index + list.size else index
        return if (i in 0 until list.size) i.toInt() else null
    }

    /** `addListRangeReply`'s arithmetic: the inclusive range left inside the list, or `null` if none. */
    private fun clamp(
        start: Long,
        end: Long,
        size: Long,
    ): Pair<Int, Int>? {
        var s = if (start < 0) size + start else start
        var e = if (end < 0) size + end else end
        if (s < 0) s = 0
        if (s > e || s >= size) return null
        if (e >= size) e = size - 1
        return s.toInt() to e.toInt()
    }

    private fun removeIfEmpty(
        db: Db,
        key: ByteArray,
        list: ListValue,
    ) {
        if (list.size == 0) db.delete(key)
    }
}

/** The list at [entry], or a thrown `WRONGTYPE` — Redis's `checkType` for lists. */
internal fun listValueOf(entry: Entry?): ListValue? =
    when (val value = entry?.value) {
        null -> null
        is ListValue -> value
        else -> throw ReplyException(WRONGTYPE)
    }
