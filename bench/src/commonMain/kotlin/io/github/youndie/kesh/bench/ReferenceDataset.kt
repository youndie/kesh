package io.github.youndie.kesh.bench

/** The six parts of research appendix A, with the counts and user-data totals it gives. */
enum class Part(
    val keys: Long,
    val userBytes: Long,
) {
    SESSIONS(10_000_000, 2_800_000_000),
    PROFILES(2_000_000, 900_000_000),
    COUNTERS(3_000_000, 50_000_000),
    FEEDS(300_000, 150_000_000),
    TAGS(500_000, 60_000_000),
    LEADERBOARDS(2_000, 300_000_000),
}

/**
 * Research appendix A as a deterministic stream of [Entry], one part after another.
 *
 * Every part draws from its own generator forked from [seed], so changing how one part is generated
 * never moves another. [scale] multiplies every key count and keeps every shape — `0.01` is a
 * 158 000-key dataset for tests, `1.0` the full 15.8 M.
 *
 * **The parameters below are the table read backwards.** Appendix A gives each part a key count, a
 * range and a total; the ranges drawn uniformly miss most totals (and the leaderboards by 5×), so each
 * part draws inside its range with the mean its total implies. The arithmetic is next to each part,
 * and the summary the generator prints checks it against the table.
 */
class ReferenceDataset(
    private val seed: Long,
    private val scale: Double = 1.0,
) {
    init {
        require(scale > 0 && scale <= 1) { "scale must be in (0, 1]: $scale" }
    }

    fun keyCount(part: Part): Int = maxOf(1L, (part.keys * scale).toLong()).toInt()

    fun entries(): Sequence<Entry> =
        sequence {
            val root = Rng(seed)
            val streams = Part.entries.associateWith { root.fork(it.ordinal.toLong()) }
            yieldAll(sessions(streams.getValue(Part.SESSIONS)))
            yieldAll(profiles(streams.getValue(Part.PROFILES)))
            yieldAll(counters(streams.getValue(Part.COUNTERS)))
            yieldAll(feeds(streams.getValue(Part.FEEDS)))
            yieldAll(tags(streams.getValue(Part.TAGS)))
            yieldAll(leaderboards(streams.getValue(Part.LEADERBOARDS)))
        }

    /**
     * `session:<uuid>` → a string of 150–400 bytes; 30 % with a TTL of 1–24 h.
     * 280 B per key: a 44-byte key leaves a mean value of 236 bytes.
     */
    private fun sessions(rng: Rng) =
        sequence {
            val length = SkewedRange(150, 400, mean = 236.0)
            repeat(keyCount(Part.SESSIONS)) {
                val key = "session:${uuid(rng)}".encodeToByteArray()
                val value = text(rng, length.next(rng))
                val ttl = if (rng.nextDouble() < 0.30) rng.nextInt(3_600, 86_400 + 1) else null
                yield(StringEntry(Part.SESSIONS, key, value, ttl))
            }
        }

    /**
     * `user:<id>` → a hash of 8–20 fields, each field and value together 10–60 bytes.
     * 450 B per key: a key of about 11 bytes and 14 fields leaves a mean pair of 31 bytes.
     * `user:1001` is Ada on the pro plan, the fixed value the scenarios use.
     */
    private fun profiles(rng: Rng) =
        sequence {
            // 31.3 bytes is the pair the total needs; draws are truncated to whole bytes, which costs
            // about 0.1, so the distribution aims that much higher (measured at full scale, B-03).
            val pair = SkewedRange(10, 60, mean = 31.41)
            for (id in 1..keyCount(Part.PROFILES)) {
                val count = rng.nextInt(8, 20 + 1)
                val fields =
                    List(count) { i ->
                        val field = PROFILE_FIELDS[i]
                        field.encodeToByteArray() to text(rng, maxOf(1, pair.next(rng) - field.length))
                    }.toMutableList()
                if (id == FIXED_USER) {
                    fields[0] = "name".encodeToByteArray() to "Ada".encodeToByteArray()
                    fields[1] = "plan".encodeToByteArray() to "pro".encodeToByteArray()
                }
                yield(HashEntry(Part.PROFILES, "user:$id".encodeToByteArray(), fields))
            }
        }

    /**
     * `rate:<id>:<minute>` → an integer, TTL 2 minutes. 16.7 B per key: 100 000 ids over 30 minutes
     * of the day (16:40–17:09) give keys of about 15 bytes, and counts of 1–60 about 2 more.
     */
    private fun counters(rng: Rng) =
        sequence {
            val minutes = 30
            val ids = (keyCount(Part.COUNTERS) + minutes - 1) / minutes
            var emitted = 0
            outer@ for (id in 1..ids) {
                for (minute in 1000 until 1000 + minutes) {
                    if (emitted == keyCount(Part.COUNTERS)) break@outer
                    val value = rng.nextInt(1, 60 + 1).toString().encodeToByteArray()
                    yield(StringEntry(Part.COUNTERS, "rate:$id:$minute".encodeToByteArray(), value, ttlSeconds = 120))
                    emitted++
                }
            }
        }

    /**
     * `feed:<id>` → a list of 20–200 item ids of 4–5 digits.
     * 500 B per key: an 11-byte key and ids of 4.9 bytes give a mean of 100 items.
     */
    private fun feeds(rng: Rng) =
        sequence {
            val length = SkewedRange(20, 200, mean = 99.5)
            for (id in 1..keyCount(Part.FEEDS)) {
                val items = List(length.next(rng)) { rng.nextInt(1_000, 100_000).toString().encodeToByteArray() }
                yield(ListEntry(Part.FEEDS, "feed:$id".encodeToByteArray(), items))
            }
        }

    /**
     * `post:<id>:tags` → a set of 3–15 members from a vocabulary of 4 096 tags.
     * 120 B per key: a 16-byte key and 9 members leave a mean tag of 11.6 bytes.
     */
    private fun tags(rng: Rng) =
        sequence {
            val vocabulary = List(TAG_VOCABULARY) { text(rng, rng.nextInt(6, 17 + 1), alphabet = LOWER) }
            for (id in 1..keyCount(Part.TAGS)) {
                val chosen = LinkedHashSet<Int>()
                val count = rng.nextInt(3, 15 + 1)
                while (chosen.size < count) chosen += rng.nextInt(0, TAG_VOCABULARY)
                yield(SetEntry(Part.TAGS, "post:$id:tags".encodeToByteArray(), chosen.map { vocabulary[it] }))
            }
        }

    /**
     * `board:<name>` → a sorted set of 1 000–100 000 members `u<user id>` with whole-number scores.
     * 150 000 B per key: a member of 7.4 bytes plus an 8-byte score gives a mean of about 9 700
     * members — which a power law on [1 000, 100 000] produces and a uniform draw does not
     * ([PowerLaw]). The first board is `board:2026-09`, the fixed value the scenarios use.
     */
    private fun leaderboards(rng: Rng) =
        sequence {
            val sizes = PowerLaw(1_000.0, 100_000.0, mean = 9_730.0).stratified(keyCount(Part.LEADERBOARDS), rng)
            repeat(keyCount(Part.LEADERBOARDS)) { index ->
                val name = if (index == 0) FIXED_BOARD else "board:g$index"
                val count = sizes[index]
                val users = LinkedHashSet<Int>()
                while (users.size < count) users += rng.nextInt(1, Part.PROFILES.keys.toInt() + 1)
                val members = users.map { "u$it".encodeToByteArray() }
                val scores = LongArray(count) { rng.nextLong(0, 1_000_000) }
                yield(SortedSetEntry(Part.LEADERBOARDS, name.encodeToByteArray(), members, scores))
            }
        }

    private fun uuid(rng: Rng): String {
        val hex = "0123456789abcdef"
        val high = rng.nextLong()
        val low = rng.nextLong()
        return buildString(36) {
            for (i in 0 until 32) {
                if (i == 8 || i == 12 || i == 16 || i == 20) append('-')
                val bits = if (i < 16) high ushr (60 - 4 * i) else low ushr (60 - 4 * (i - 16))
                append(hex[(bits and 0xF).toInt()])
            }
        }
    }

    private fun text(
        rng: Rng,
        length: Int,
        alphabet: String = PRINTABLE,
    ): ByteArray = ByteArray(length) { alphabet[rng.nextInt(0, alphabet.length)].code.toByte() }

    companion object {
        const val FIXED_USER = 1001
        const val FIXED_BOARD = "board:2026-09"
        const val TAG_VOCABULARY = 4096

        private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
        private const val PRINTABLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        val PROFILE_FIELDS =
            listOf(
                "name",
                "plan",
                "visits",
                "email",
                "country",
                "city",
                "locale",
                "theme",
                "tz",
                "created",
                "updated",
                "avatar",
                "phone",
                "company",
                "role",
                "team",
                "score",
                "badge",
                "referrer",
                "status",
            )
    }
}
