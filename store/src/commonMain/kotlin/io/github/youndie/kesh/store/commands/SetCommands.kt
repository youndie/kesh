package io.github.youndie.kesh.store.commands

import io.github.youndie.kesh.resp.Reply
import io.github.youndie.kesh.store.Db
import io.github.youndie.kesh.store.commands.Replies.NOT_AN_INTEGER
import io.github.youndie.kesh.store.commands.Replies.ONE
import io.github.youndie.kesh.store.commands.Replies.SYNTAX
import io.github.youndie.kesh.store.commands.Replies.WRONGTYPE
import io.github.youndie.kesh.store.commands.Replies.ZERO
import io.github.youndie.kesh.store.commands.Replies.long
import io.github.youndie.kesh.store.keyspace.Entry
import io.github.youndie.kesh.store.sets.SetValue
import kotlin.random.Random

/**
 * The set commands (`endpoint-sets`) the brief lists, following `redis/redis@7.2!/src/t_set.c`
 * function by function. `SSCAN` is B-10's. Replies of several members come in the set's own order,
 * which is not Redis's (research D-22); Redis promises none.
 */
object SetCommands {
    private val EMPTY_ARRAY: Reply = Reply.Multi(emptyList())
    private val MUST_BE_POSITIVE: Reply = Reply.Error("ERR value is out of range, must be positive")

    /** `getRangeLongFromObjectOrReply(-LONG_MAX, LONG_MAX)`, worded as Redis formats it. */
    private val COUNT_OUT_OF_RANGE: Reply =
        Reply.Error("ERR value is out of range, value must between -9223372036854775807 and 9223372036854775807")

    /** Where `SPOP` and `SRANDMEMBER` draw from. Replaceable so tests can fix the sequence. */
    var random: Random = Random.Default

    val all: List<StoreCommand> =
        listOf(
            StoreCommand("sadd", -3) { db, a -> answering { sadd(db, a) } },
            StoreCommand("srem", -3) { db, a -> answering { srem(db, a) } },
            StoreCommand("sismember", 3) { db, a ->
                answering { if (setValueOf(db.lookup(a[1]))?.contains(a[2]) == true) ONE else ZERO }
            },
            StoreCommand("smismember", -3) { db, a ->
                answering {
                    val set = setValueOf(db.lookup(a[1]))
                    Reply.Multi((2 until a.size).map { if (set?.contains(a[it]) == true) ONE else ZERO })
                }
            },
            StoreCommand("smembers", 2) { db, a -> answering { sinter(db, a) } },
            StoreCommand(
                "scard",
                2,
            ) { db, a -> answering { Reply.Integer((setValueOf(db.lookup(a[1]))?.size ?: 0).toLong()) } },
            StoreCommand("spop", -2) { db, a -> answering { spop(db, a) } },
            StoreCommand("srandmember", -2) { db, a -> answering { srandmember(db, a) } },
            StoreCommand("sinter", -2) { db, a -> answering { sinter(db, a) } },
            StoreCommand("sunion", -2) { db, a -> answering { sunion(db, a) } },
            StoreCommand("sdiff", -2) { db, a -> answering { sdiff(db, a) } },
        )

    /** `saddCommand`: a set created with more members than a packed one holds starts as a table. */
    private fun sadd(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        val set = setValueOf(db.lookup(a[1])) ?: SetValue(db.seed).also { db.set(a[1], it) }
        set.prepareFor(a.size - 2)
        var added = 0L
        for (i in 2 until a.size) if (set.add(a[i])) added++
        return Reply.Integer(added)
    }

    /** `sremCommand`: the key goes with its last member, and the loop stops there. */
    private fun srem(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        val set = setValueOf(db.lookup(a[1])) ?: return ZERO
        var removed = 0L
        for (i in 2 until a.size) {
            if (set.remove(a[i])) {
                removed++
                if (set.size == 0) {
                    db.delete(a[1])
                    break
                }
            }
        }
        return Reply.Integer(removed)
    }

    /** `spopCommand`: a second argument is a count; a third is a syntax error, not an arity one. */
    private fun spop(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        if (a.size > 3) return SYNTAX
        if (a.size == 3) return spopWithCount(db, a)
        val set = setValueOf(db.lookup(a[1])) ?: return Reply.NULL_BULK
        val member = set.random(random)
        set.remove(member)
        if (set.size == 0) db.delete(a[1])
        return Reply.Bulk(member)
    }

    /** `spopWithCountCommand`: a count reaching the size takes the whole set, and the key. */
    private fun spopWithCount(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        val count = long(a[2])?.takeIf { it >= 0 } ?: return MUST_BE_POSITIVE
        val set = setValueOf(db.lookup(a[1])) ?: return EMPTY_ARRAY
        if (count == 0L) return EMPTY_ARRAY
        if (count >= set.size) {
            val members = set.members()
            db.delete(a[1])
            return bulks(members)
        }
        val popped = set.members().shuffled(random).take(count.toInt())
        popped.forEach { set.remove(it) }
        return bulks(popped)
    }

    /** `srandmemberCommand`: as `SPOP`, a third argument is a syntax error. */
    private fun srandmember(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        if (a.size > 3) return SYNTAX
        if (a.size == 3) return srandmemberWithCount(db, a)
        val set = setValueOf(db.lookup(a[1])) ?: return Reply.NULL_BULK
        return Reply.Bulk(set.random(random))
    }

    /**
     * `srandmemberWithCountCommand`: a negative count may repeat members and is always that long; a
     * positive one never repeats and stops at the whole set.
     */
    private fun srandmemberWithCount(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        val l = long(a[2]) ?: return NOT_AN_INTEGER
        if (l == Long.MIN_VALUE) return COUNT_OUT_OF_RANGE
        val set = setValueOf(db.lookup(a[1])) ?: return EMPTY_ARRAY
        if (l == 0L) return EMPTY_ARRAY
        if (l < 0 || l == 1L) {
            val count = if (l < 0) -l else l
            return Reply.Multi(List(count.toInt()) { Reply.Bulk(set.random(random)) })
        }
        if (l >= set.size) return bulks(set.members())
        return bulks(set.members().shuffled(random).take(l.toInt()))
    }

    /**
     * `sinterGenericCommand` — and `SMEMBERS`, which Redis 7.2 answers with it. Every key is looked at
     * and type-checked, in order, before a missing one makes the answer empty.
     */
    private fun sinter(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        var empty = false
        val sets = ArrayList<SetValue>()
        for (i in 1 until a.size) {
            val set = setValueOf(db.lookup(a[i]))
            if (set == null) empty = true else sets.add(set)
        }
        if (empty) return EMPTY_ARRAY
        sets.sortBy { it.size }
        val result = sets[0].members().filter { member -> (1 until sets.size).all { sets[it].contains(member) } }
        return bulks(result)
    }

    /** `sunionDiffGenericCommand` for a union: a missing key is an empty set. */
    private fun sunion(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        val sets = (1 until a.size).mapNotNull { setValueOf(db.lookup(a[it])) }
        val union = SetValue(db.seed)
        union.prepareFor(sets.sumOf { it.size })
        sets.forEach { set -> set.forEach { union.add(it) } }
        return bulks(union.members())
    }

    /** `sunionDiffGenericCommand` for a difference: the first set's members in none of the others. */
    private fun sdiff(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        val sets = (1 until a.size).map { setValueOf(db.lookup(a[it])) }
        val first = sets[0] ?: return EMPTY_ARRAY
        val others = sets.drop(1).filterNotNull()
        return bulks(first.members().filter { member -> others.none { it.contains(member) } })
    }

    private fun bulks(members: List<ByteArray>): Reply = Reply.Multi(members.map { Reply.Bulk(it) })
}

/** The set at [entry], or a thrown `WRONGTYPE` — Redis's `checkType` for sets. */
internal fun setValueOf(entry: Entry?): SetValue? =
    when (val value = entry?.value) {
        null -> null
        is SetValue -> value
        else -> throw ReplyException(WRONGTYPE)
    }
