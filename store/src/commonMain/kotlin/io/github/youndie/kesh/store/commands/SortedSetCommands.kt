package io.github.youndie.kesh.store.commands

import io.github.youndie.kesh.resp.Reply
import io.github.youndie.kesh.store.Db
import io.github.youndie.kesh.store.RedisFloat
import io.github.youndie.kesh.store.commands.Replies.NOT_AN_INTEGER
import io.github.youndie.kesh.store.commands.Replies.NOT_A_FLOAT
import io.github.youndie.kesh.store.commands.Replies.SYNTAX
import io.github.youndie.kesh.store.commands.Replies.WRONGTYPE
import io.github.youndie.kesh.store.commands.Replies.ZERO
import io.github.youndie.kesh.store.commands.Replies.long
import io.github.youndie.kesh.store.commands.Replies.wrongArity
import io.github.youndie.kesh.store.keyspace.Entry
import io.github.youndie.kesh.store.zsets.ZSetValue
import io.github.youndie.kesh.store.zsets.ZSetValue.LexBound

/**
 * The sorted set commands (`endpoint-sorted-sets`) the brief lists, following
 * `redis/redis@7.2!/src/t_zset.c` function by function. `ZSCAN` is B-10's. Scores are read as
 * `string2d` reads them, range bounds as bare `strtod` does, and printed as `d2string` prints them
 * ([RedisFloat]).
 */
object SortedSetCommands {
    private val EMPTY_ARRAY: Reply = Reply.Multi(emptyList())
    private val NULL_ARRAY: Reply = Reply.Multi(null)
    private val MUST_BE_POSITIVE: Reply = Reply.Error("ERR value is out of range, must be positive")
    private val NOT_A_FLOAT_RANGE: Reply = Reply.Error("ERR min or max is not a float")
    private val NOT_A_LEX_RANGE: Reply = Reply.Error("ERR min or max not valid string range item")

    val all: List<StoreCommand> =
        listOf(
            StoreCommand("zadd", -4) { db, a -> answering { zadd(db, a, incr = false) } },
            StoreCommand("zincrby", 4) { db, a -> answering { zadd(db, a, incr = true) } },
            StoreCommand("zrem", -3) { db, a -> answering { zrem(db, a) } },
            StoreCommand(
                "zcard",
                2,
            ) { db, a -> answering { Reply.Integer((zsetOf(db.lookup(a[1]))?.size ?: 0).toLong()) } },
            StoreCommand("zscore", 3) { db, a ->
                answering { score(zsetOf(db.lookup(a[1]))?.score(a[2])) }
            },
            StoreCommand("zmscore", -3) { db, a ->
                answering {
                    val zset = zsetOf(db.lookup(a[1]))
                    Reply.Multi((2 until a.size).map { score(zset?.score(a[it])) })
                }
            },
            StoreCommand("zrank", -3) { db, a -> answering { zrank(db, a, "zrank", reverse = false) } },
            StoreCommand("zrevrank", -3) { db, a -> answering { zrank(db, a, "zrevrank", reverse = true) } },
            StoreCommand("zcount", 4) { db, a -> answering { zcount(db, a) } },
            StoreCommand("zrange", -4) { db, a -> answering { zrange(db, a, Type.AUTO, reverse = null) } },
            StoreCommand("zrevrange", -4) { db, a -> answering { zrange(db, a, Type.RANK, reverse = true) } },
            StoreCommand("zremrangebyscore", 4) { db, a -> answering { zremrange(db, a, byScore = true) } },
            StoreCommand("zremrangebyrank", 4) { db, a -> answering { zremrange(db, a, byScore = false) } },
            StoreCommand("zpopmin", -2) { db, a -> answering { zpop(db, a, max = false) } },
            StoreCommand("zpopmax", -2) { db, a -> answering { zpop(db, a, max = true) } },
        )

    private enum class Type { AUTO, RANK, SCORE, LEX }

    /**
     * `zaddGenericCommand` (and `ZINCRBY`, which is `ZADD INCR` with its own arity): options, then the
     * pairs' shape, then option conflicts, then every score, then the key.
     */
    private fun zadd(
        db: Db,
        a: List<ByteArray>,
        incr: Boolean,
    ): Reply {
        var nx = false
        var xx = false
        var gt = false
        var lt = false
        var ch = false
        var isIncr = incr
        var scoreIndex = 2
        while (scoreIndex < a.size) {
            when (a[scoreIndex].decodeToString().lowercase()) {
                "nx" -> nx = true
                "xx" -> xx = true
                "ch" -> ch = true
                "incr" -> isIncr = true
                "gt" -> gt = true
                "lt" -> lt = true
                else -> break
            }
            scoreIndex++
        }
        val arguments = a.size - scoreIndex
        if (arguments % 2 != 0 || arguments == 0) return SYNTAX
        val elements = arguments / 2
        if (nx && xx) return Reply.Error("ERR XX and NX options at the same time are not compatible")
        if ((gt && nx) || (lt && nx) || (gt && lt)) {
            return Reply.Error("ERR GT, LT, and/or NX options at the same time are not compatible")
        }
        if (isIncr && elements > 1) return Reply.Error("ERR INCR option supports a single increment-element pair")
        val scores = DoubleArray(elements)
        for (j in 0 until elements) {
            scores[j] = RedisFloat.parseScore(a[scoreIndex + 2 * j].decodeToString()) ?: return NOT_A_FLOAT
        }
        var zset = zsetOf(db.lookup(a[1]))
        var added = 0L
        var updated = 0L
        var processed = 0
        var lastScore = 0.0
        if (zset == null && xx) return if (isIncr) Reply.NULL_BULK else ZERO
        if (zset == null) {
            zset = ZSetValue(db.seed).also { db.set(a[1], it) }
        }
        zset.prepareFor(elements)
        for (j in 0 until elements) {
            val member = a[scoreIndex + 2 * j + 1]
            var score = scores[j]
            val current = zset.score(member)
            if (current != null) {
                if (nx) continue
                if (isIncr) {
                    score += current
                    if (score.isNaN()) return Reply.Error("ERR resulting score is not a number (NaN)")
                }
                if ((lt && score >= current) || (gt && score <= current)) continue
                if (score != current) {
                    zset.put(member, score)
                    updated++
                }
            } else {
                if (xx) continue
                zset.put(member, score)
                added++
            }
            processed++
            lastScore = score
        }
        if (isIncr) {
            return if (processed >
                0
            ) {
                Reply.Bulk(RedisFloat.formatScore(lastScore).encodeToByteArray())
            } else {
                Reply.NULL_BULK
            }
        }
        return Reply.Integer(if (ch) added + updated else added)
    }

    /** `zremCommand`: the key goes with its last member, and the loop stops there. */
    private fun zrem(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        val zset = zsetOf(db.lookup(a[1])) ?: return ZERO
        var deleted = 0L
        for (i in 2 until a.size) {
            if (zset.remove(a[i])) deleted++
            if (zset.size == 0) {
                db.delete(a[1])
                break
            }
        }
        return Reply.Integer(deleted)
    }

    /** `zrankGenericCommand`, with 7.2's `WITHSCORE`: a fourth argument is the arity error. */
    private fun zrank(
        db: Db,
        a: List<ByteArray>,
        name: String,
        reverse: Boolean,
    ): Reply {
        if (a.size > 4) return wrongArity(name)
        val withScore = a.size == 4
        if (withScore && !a[3].decodeToString().equals("withscore", ignoreCase = true)) return SYNTAX
        val missing = if (withScore) NULL_ARRAY else Reply.NULL_BULK
        val zset = zsetOf(db.lookup(a[1])) ?: return missing
        val ascending = zset.rank(a[2]) ?: return missing
        val rank = Reply.Integer((if (reverse) zset.size - 1 - ascending else ascending).toLong())
        return if (withScore) Reply.Multi(listOf(rank, score(zset.score(a[2])))) else rank
    }

    /** `zcountCommand`: the range is read before the key. */
    private fun zcount(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        val range = scoreRange(a[2], a[3]) ?: return NOT_A_FLOAT_RANGE
        val zset = zsetOf(db.lookup(a[1])) ?: return ZERO
        val (from, to) = range.ranks(zset)
        return Reply.Integer(maxOf(0, to - from).toLong())
    }

    /**
     * `zrangeGenericCommand`: `ZRANGE` takes `BYSCORE`/`BYLEX`, `REV`, `LIMIT` and `WITHSCORES`;
     * `ZREVRANGE` arrives with its type and direction already fixed, so those words are syntax errors
     * there. Options, then their conflicts, then the bounds, then the key.
     */
    private fun zrange(
        db: Db,
        a: List<ByteArray>,
        fixedType: Type,
        reverse: Boolean?,
    ): Reply {
        var type = fixedType
        var direction = reverse
        var withScores = false
        var offset = 0L
        var limit = -1L
        var j = 4
        while (j < a.size) {
            val option = a[j].decodeToString().lowercase()
            val left = a.size - j - 1
            when {
                option == "withscores" -> {
                    withScores = true
                }

                option == "limit" && left >= 2 -> {
                    offset = long(a[j + 1]) ?: return NOT_AN_INTEGER
                    limit = long(a[j + 2]) ?: return NOT_AN_INTEGER
                    j += 2
                }

                direction == null && option == "rev" -> {
                    direction = true
                }

                type == Type.AUTO && option == "bylex" -> {
                    type = Type.LEX
                }

                type == Type.AUTO && option == "byscore" -> {
                    type = Type.SCORE
                }

                else -> {
                    return SYNTAX
                }
            }
            j++
        }
        val rev = direction ?: false
        if (type == Type.AUTO) type = Type.RANK
        if (limit != -1L && type == Type.RANK) {
            return Reply.Error("ERR syntax error, LIMIT is only supported in combination with either BYSCORE or BYLEX")
        }
        if (withScores && type == Type.LEX) {
            return Reply.Error("ERR syntax error, WITHSCORES not supported in combination with BYLEX")
        }
        val (minArg, maxArg) = if (rev && type != Type.RANK) a[3] to a[2] else a[2] to a[3]
        val ranks: (ZSetValue) -> Pair<Int, Int> =
            when (type) {
                Type.RANK -> {
                    val start = long(minArg) ?: return NOT_AN_INTEGER
                    val end = long(maxArg) ?: return NOT_AN_INTEGER
                    return rankRange(db, a[1], start, end, rev, withScores)
                }

                Type.SCORE -> {
                    val range = scoreRange(minArg, maxArg) ?: return NOT_A_FLOAT_RANGE
                    range::ranks
                }

                else -> {
                    val range = lexRange(minArg, maxArg) ?: return NOT_A_LEX_RANGE
                    range::ranks
                }
            }
        val zset = zsetOf(db.lookup(a[1])) ?: return EMPTY_ARRAY
        if (offset > 0 && offset >= zset.size) return EMPTY_ARRAY
        val (from, to) = ranks(zset)
        // `while (ln && offset--)` and `while (ln && limit--)`: a negative offset skips everything, a
        // negative limit takes everything.
        if (offset < 0 || from >= to) return EMPTY_ARRAY
        val available = to - from - offset
        if (available <= 0) return EMPTY_ARRAY
        val taken = if (limit < 0) available else minOf(limit, available)
        if (taken <= 0) return EMPTY_ARRAY
        val items =
            if (rev) {
                val top = to - 1 - offset.toInt()
                zset.range(top - taken.toInt() + 1, top, reverse = true)
            } else {
                val bottom = from + offset.toInt()
                zset.range(bottom, bottom + taken.toInt() - 1)
            }
        return emit(items, withScores)
    }

    /** `genericZrangebyrankCommand`: a reverse range counts its ranks from the top. */
    private fun rankRange(
        db: Db,
        key: ByteArray,
        start: Long,
        end: Long,
        reverse: Boolean,
        withScores: Boolean,
    ): Reply {
        val zset = zsetOf(db.lookup(key)) ?: return EMPTY_ARRAY
        val (from, to) = clampRanks(start, end, zset.size) ?: return EMPTY_ARRAY
        val items =
            if (reverse) {
                zset.range(zset.size - 1 - to, zset.size - 1 - from, reverse = true)
            } else {
                zset.range(from, to)
            }
        return emit(items, withScores)
    }

    /** `zremrangeGenericCommand`: the range is read before the key. */
    private fun zremrange(
        db: Db,
        a: List<ByteArray>,
        byScore: Boolean,
    ): Reply {
        val ranks: (ZSetValue) -> Pair<Int, Int>?
        if (byScore) {
            val range = scoreRange(a[2], a[3]) ?: return NOT_A_FLOAT_RANGE
            ranks = { z -> range.ranks(z).let { (from, to) -> if (from < to) from to to - 1 else null } }
        } else {
            val start = long(a[2]) ?: return NOT_AN_INTEGER
            val end = long(a[3]) ?: return NOT_AN_INTEGER
            ranks = { z -> clampRanks(start, end, z.size) }
        }
        val zset = zsetOf(db.lookup(a[1])) ?: return ZERO
        val (from, to) = ranks(zset) ?: return ZERO
        val removed = zset.removeRange(from, to)
        if (zset.size == 0) db.delete(a[1])
        return Reply.Integer(removed.toLong())
    }

    /** `zpopMinMaxCommand` and `genericZpopCommand`: a missing key is an empty array, count or not. */
    private fun zpop(
        db: Db,
        a: List<ByteArray>,
        max: Boolean,
    ): Reply {
        if (a.size > 3) return SYNTAX
        val count = if (a.size == 3) long(a[2])?.takeIf { it >= 0 } ?: return MUST_BE_POSITIVE else 1L
        val zset = zsetOf(db.lookup(a[1])) ?: return EMPTY_ARRAY
        if (count == 0L) return EMPTY_ARRAY
        val taken = minOf(count, zset.size.toLong()).toInt()
        val items =
            if (max) zset.range(zset.size - taken, zset.size - 1, reverse = true) else zset.range(0, taken - 1)
        items.forEach { zset.remove(it.first) }
        if (zset.size == 0) db.delete(a[1])
        return emit(items, withScores = true)
    }

    private fun emit(
        items: List<Pair<ByteArray, Double>>,
        withScores: Boolean,
    ): Reply {
        val out = ArrayList<Reply>(if (withScores) items.size * 2 else items.size)
        for ((member, score) in items) {
            out.add(Reply.Bulk(member))
            if (withScores) out.add(score(score))
        }
        return Reply.Multi(out)
    }

    private fun score(value: Double?): Reply =
        value?.let { Reply.Bulk(RedisFloat.formatScore(it).encodeToByteArray()) } ?: Reply.NULL_BULK

    /** A rank range's arithmetic, as `LRANGE`'s: the inclusive ranks left inside the set, or `null`. */
    private fun clampRanks(
        start: Long,
        end: Long,
        size: Int,
    ): Pair<Int, Int>? {
        var s = if (start < 0) size + start else start
        var e = if (end < 0) size + end else end
        if (s < 0) s = 0
        if (s > e || s >= size) return null
        if (e >= size) e = size.toLong() - 1
        return s.toInt() to e.toInt()
    }

    /** `zrangespec`: two scores, each inclusive or not. */
    private class ScoreRange(
        val min: Double,
        val minExclusive: Boolean,
        val max: Double,
        val maxExclusive: Boolean,
    ) {
        /** The ranks in range as `[from, to)`. */
        fun ranks(zset: ZSetValue): Pair<Int, Int> =
            zset.countBelowScore(min, orEqual = minExclusive) to zset.countBelowScore(max, orEqual = !maxExclusive)
    }

    /** `zslParseRange`: `(` makes a bound exclusive; the number is bare `strtod`'s. */
    private fun scoreRange(
        min: ByteArray,
        max: ByteArray,
    ): ScoreRange? {
        fun bound(bytes: ByteArray): Pair<Double, Boolean>? {
            val text = bytes.decodeToString()
            val exclusive = text.startsWith("(")
            val value = RedisFloat.parseRangeBound(if (exclusive) text.substring(1) else text) ?: return null
            return value to exclusive
        }
        val (lo, loEx) = bound(min) ?: return null
        val (hi, hiEx) = bound(max) ?: return null
        return ScoreRange(lo, loEx, hi, hiEx)
    }

    /** `zlexrangespec`: `-`, `+`, or a member after `[` (inclusive) or `(` (exclusive). */
    private class LexRange(
        val min: LexBound,
        val minExclusive: Boolean,
        val max: LexBound,
        val maxExclusive: Boolean,
    ) {
        fun ranks(zset: ZSetValue): Pair<Int, Int> =
            zset.countBelowMember(min, orEqual = minExclusive) to zset.countBelowMember(max, orEqual = !maxExclusive)
    }

    /** `zslParseLexRange` and `zslParseLexRangeItem`. */
    private fun lexRange(
        min: ByteArray,
        max: ByteArray,
    ): LexRange? {
        fun bound(bytes: ByteArray): Pair<LexBound, Boolean>? {
            if (bytes.isEmpty()) return null
            return when (bytes[0].toInt().toChar()) {
                '+' -> if (bytes.size == 1) LexBound.Max to false else null
                '-' -> if (bytes.size == 1) LexBound.Min to false else null
                '(' -> LexBound.Value(bytes.copyOfRange(1, bytes.size)) to true
                '[' -> LexBound.Value(bytes.copyOfRange(1, bytes.size)) to false
                else -> null
            }
        }
        val (lo, loEx) = bound(min) ?: return null
        val (hi, hiEx) = bound(max) ?: return null
        return LexRange(lo, loEx, hi, hiEx)
    }
}

/** The sorted set at [entry], or a thrown `WRONGTYPE` — Redis's `checkType` for sorted sets. */
internal fun zsetOf(entry: Entry?): ZSetValue? =
    when (val value = entry?.value) {
        null -> null
        is ZSetValue -> value
        else -> throw ReplyException(WRONGTYPE)
    }
