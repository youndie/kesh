package io.github.youndie.kesh.store.commands

import io.github.youndie.kesh.resp.Reply
import io.github.youndie.kesh.store.Db
import io.github.youndie.kesh.store.Glob
import io.github.youndie.kesh.store.RedisFloat
import io.github.youndie.kesh.store.commands.Replies.SYNTAX
import io.github.youndie.kesh.store.commands.Replies.long
import io.github.youndie.kesh.store.hashes.HashValue
import io.github.youndie.kesh.store.keyspace.Entry
import io.github.youndie.kesh.store.keyspace.Keyspace
import io.github.youndie.kesh.store.sets.SetValue
import io.github.youndie.kesh.store.zsets.SkipList
import io.github.youndie.kesh.store.zsets.ZSetValue

/**
 * `SCAN`, `HSCAN`, `SSCAN`, `ZSCAN`: `scanGenericCommand` in `redis/redis@7.2!/src/db.c`, over
 * [Keyspace.scan]. A packed collection answers whole, with cursor 0, as Redis's small encodings do;
 * a table is walked a few buckets per call — `COUNT` entries sampled, at most ten times as many
 * buckets visited.
 */
object ScanCommands {
    private val INVALID_CURSOR: Reply = Reply.Error("ERR invalid cursor")

    /** `shared.emptyscan`: what a scan of a missing key answers. */
    private val EMPTY_SCAN: Reply = Reply.Multi(listOf(Reply.Bulk("0".encodeToByteArray()), Reply.Multi(emptyList())))

    val all: List<StoreCommand> =
        listOf(
            StoreCommand("scan", -2) { db, a -> answering { scan(db, a) } },
            StoreCommand("hscan", -3) { db, a -> answering { scanKey(db, a) { hashOf(it) } } },
            StoreCommand("sscan", -3) { db, a -> answering { scanKey(db, a) { setValueOf(it) } } },
            StoreCommand("zscan", -3) { db, a -> answering { scanKey(db, a) { zsetOf(it) } } },
        )

    private class Options(
        val count: Long,
        val pattern: ByteArray?,
        val type: String?,
    )

    /** `scanCommand`: the cursor, then the options, then the walk, then expired and mistyped keys out. */
    private fun scan(
        db: Db,
        a: List<ByteArray>,
    ): Reply {
        val cursor = parseCursor(a[1]) ?: return INVALID_CURSOR
        val options = options(a, from = 2, keyScan = false).getOrElse { return (it as ReplyException).reply }
        val found = ArrayList<ByteArray>()
        val next = walk(db.keyspace, cursor, options) { found.add(it.key) }
        // After the walk, as Redis does it: a key that has expired is deleted and left out; with TYPE,
        // a key of another type is left out.
        val keys =
            found.filter { key ->
                val entry = db.lookup(key) ?: return@filter false
                options.type == null || typeNameOf(entry.value) == options.type
            }
        return reply(next, keys)
    }

    /** `hscanCommand`, `sscanCommand`, `zscanCommand`: the cursor, then the key, then the options. */
    private fun scanKey(
        db: Db,
        a: List<ByteArray>,
        valueOf: (Entry?) -> Any?,
    ): Reply {
        val cursor = parseCursor(a[2]) ?: return INVALID_CURSOR
        val value = valueOf(db.lookup(a[1])) ?: return EMPTY_SCAN
        val options = options(a, from = 3, keyScan = true).getOrElse { return (it as ReplyException).reply }
        val items = ArrayList<ByteArray>()
        val matches = { bytes: ByteArray -> options.pattern == null || Glob.matches(options.pattern, bytes) }
        val next =
            when (value) {
                is HashValue -> {
                    val table = value.table
                    if (table == null) {
                        value.forEach { field, v -> if (matches(field)) items += listOf(field, v) }
                        0L
                    } else {
                        walk(table, cursor, options) { items += listOf(it.key, it.value as ByteArray) }
                    }
                }

                is SetValue -> {
                    val table = value.table
                    if (table == null) {
                        value.forEach { if (matches(it)) items.add(it) }
                        0L
                    } else {
                        walk(table, cursor, options) { items.add(it.key) }
                    }
                }

                is ZSetValue -> {
                    val index = value.index
                    if (index == null) {
                        // A packed score is stored as `d2string` wrote it (or as an integer): printed so.
                        value.range(0, value.size - 1).forEach { (member, score) ->
                            if (matches(member)) {
                                items +=
                                    listOf(member, RedisFloat.formatScore(score).encodeToByteArray())
                            }
                        }
                        0L
                    } else {
                        // `scanCallback` prints a skiplist's score with `%.17Lg`, not as ZSCORE does.
                        walk(index, cursor, options) {
                            val score = (it.value as SkipList.Node).score
                            items += listOf(it.key, RedisFloat.formatLongDoubleAuto(score).encodeToByteArray())
                        }
                    }
                }

                else -> {
                    error("not a scannable value: $value")
                }
            }
        return reply(next, items)
    }

    /**
     * The walk: `dictScan` steps until [Options.count] entries are sampled, the cursor returns to 0,
     * or ten times `COUNT` steps have run. The pattern filters what is sampled, not what counts.
     */
    private fun walk(
        table: Keyspace,
        start: Long,
        options: Options,
        collect: (Entry) -> Unit,
    ): Long {
        var cursor = start
        var iterations = if (options.count > Long.MAX_VALUE / 10) Long.MAX_VALUE else options.count * 10
        var sampled = 0L
        do {
            cursor =
                table.scan(cursor) {
                    sampled++
                    if (options.pattern == null || Glob.matches(options.pattern, it.key)) collect(it)
                }
        } while (cursor != 0L && iterations-- != 0L && sampled < options.count)
        return cursor
    }

    /** `scanGenericCommand`'s option loop: `COUNT` of at least 1, `MATCH`, and `TYPE` on `SCAN` only. */
    private fun options(
        a: List<ByteArray>,
        from: Int,
        keyScan: Boolean,
    ): Result<Options> {
        var count = 10L
        var pattern: ByteArray? = null
        var type: String? = null
        var i = from
        while (i < a.size) {
            val left = a.size - i
            val option = a[i].decodeToString().lowercase()
            when {
                option == "count" && left >= 2 -> {
                    count = long(a[i + 1]) ?: return failure(Replies.NOT_AN_INTEGER)
                    if (count < 1) return failure(SYNTAX)
                }

                option == "match" && left >= 2 -> {
                    val p = a[i + 1]
                    pattern = if (p.size == 1 && p[0] == '*'.code.toByte()) null else p
                }

                option == "type" && !keyScan && left >= 2 -> {
                    // Redis 7.2 refuses no type name: the check is commented out "until Redis 8.0"
                    // (`scanGenericCommand`), so an unknown name simply matches no key.
                    type = a[i + 1].decodeToString().lowercase()
                }

                else -> {
                    return failure(SYNTAX)
                }
            }
            i += 2
        }
        return Result.success(Options(count, pattern, type))
    }

    private fun failure(reply: Reply): Result<Options> = Result.failure(ReplyException(reply))

    /**
     * `parseScanCursorOrReply`: `strtoul` in base 10 — an unsigned 64-bit number, a sign allowed (so
     * `-1` is the largest cursor), an empty string read as 0, nothing before or after it.
     */
    private fun parseCursor(bytes: ByteArray): Long? {
        val text = bytes.decodeToString()
        if (text.isEmpty()) return 0L
        if (text[0] in " \t\n\u000B\u000C\r") return null
        val negative = text[0] == '-'
        val digits = if (text[0] == '+' || text[0] == '-') text.substring(1) else text
        if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
        var value = 0uL
        for (c in digits) {
            val digit = (c - '0').toULong()
            if (value > (ULong.MAX_VALUE - digit) / 10uL) return null
            value = value * 10uL + digit
        }
        return (if (negative) (0uL - value) else value).toLong()
    }

    /** `addReplyBulkLongLong(c, cursor)`: the cursor printed as a signed number, as Redis prints it. */
    private fun reply(
        cursor: Long,
        items: List<ByteArray>,
    ): Reply =
        Reply.Multi(
            listOf(
                Reply.Bulk(cursor.toString().encodeToByteArray()),
                Reply.Multi(
                    items.map {
                        Reply.Bulk(it)
                    },
                ),
            ),
        )
}
