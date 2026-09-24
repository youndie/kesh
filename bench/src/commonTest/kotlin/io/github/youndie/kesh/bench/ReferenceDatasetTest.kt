package io.github.youndie.kesh.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReferenceDatasetTest {
    /** FNV-1a over everything the script writes: equal digests, equal bytes, for these purposes. */
    private class Digest : ByteSink {
        var value = -0x340d631b7bdddcdbL
        var length = 0L

        override fun write(
            bytes: ByteArray,
            length: Int,
        ) {
            for (i in 0 until length) {
                value = (value xor (bytes[i].toLong() and 0xFF)) * 0x100000001b3L
            }
            this.length += length
        }
    }

    private fun digest(
        seed: Long,
        scale: Double,
    ): Pair<Long, Long> {
        val sink = Digest()
        val script = LoadScript(sink, flushAt = 4096)
        ReferenceDataset(seed, scale).entries().forEach(script::write)
        script.finish()
        return sink.value to sink.length
    }

    @Test
    fun `the same seed writes the same bytes`() {
        assertEquals(digest(42, 0.0005), digest(42, 0.0005))
    }

    @Test
    fun `another seed writes other bytes`() {
        assertNotEquals(digest(42, 0.0005).first, digest(43, 0.0005).first)
    }

    /**
     * THE PIN. Seed 42 at scale 0.0005 has had this digest since B-03, on the JVM and on linuxX64
     * alike. It changing means the dataset changed, and every number measured on the old one stopped
     * being comparable with every number measured on the new one. That can be the right call — then it
     * is made on purpose, this value is replaced in the same change, and the reports say which
     * generator they used. It must never be replaced just to make this test pass.
     */
    @Test
    fun `seed 42 is the dataset it has been since B-03 on every platform`() {
        assertEquals(PINNED, digest(42, 0.0005))
    }

    @Test
    fun `every part has its scaled key count`() {
        val dataset = ReferenceDataset(7, 0.001)
        val summary = DatasetSummary(0.001)
        dataset.entries().forEach(summary::add)
        assertEquals(10_000, summary.keys(Part.SESSIONS))
        assertEquals(2_000, summary.keys(Part.PROFILES))
        assertEquals(3_000, summary.keys(Part.COUNTERS))
        assertEquals(300, summary.keys(Part.FEEDS))
        assertEquals(500, summary.keys(Part.TAGS))
        assertEquals(2, summary.keys(Part.LEADERBOARDS))
    }

    @Test
    fun `every shape stays inside the range appendix A gives`() {
        var sessions = 0
        var withTtl = 0
        ReferenceDataset(7, 0.001).entries().forEach { entry ->
            when (entry) {
                is StringEntry -> {
                    if (entry.part == Part.SESSIONS) {
                        sessions++
                        assertTrue(entry.value.size in 150..400, "session value ${entry.value.size}")
                        entry.ttlSeconds?.let {
                            withTtl++
                            assertTrue(it in 3_600..86_400)
                        }
                    } else {
                        assertEquals(120, entry.ttlSeconds)
                    }
                }

                is HashEntry -> {
                    assertTrue(entry.fields.size in 8..20)
                    // user:1001's name and plan are the scenarios' fixed values, shorter than any drawn pair.
                    val drawn = if (entry.key.decodeToString() == "user:1001") entry.fields.drop(2) else entry.fields
                    drawn.forEach { (f, v) -> assertTrue(f.size + v.size in 10..60, "pair ${f.size}+${v.size}") }
                }

                is ListEntry -> {
                    assertTrue(entry.items.size in 20..200)
                }

                is SetEntry -> {
                    assertTrue(entry.members.size in 3..15)
                    assertEquals(
                        entry.members.size,
                        entry.members
                            .map { it.decodeToString() }
                            .toSet()
                            .size,
                    )
                }

                is SortedSetEntry -> {
                    assertTrue(entry.members.size in 1_000..100_000)
                    assertEquals(
                        entry.members.size,
                        entry.members
                            .map { it.decodeToString() }
                            .toSet()
                            .size,
                    )
                }
            }
        }
        assertTrue(withTtl.toDouble() / sessions in 0.27..0.33, "TTL share ${withTtl.toDouble() / sessions}")
    }

    @Test
    fun `the fixed values the scenarios use are there`() {
        val entries = ReferenceDataset(7, 0.001).entries()
        val ada = entries.filterIsInstance<HashEntry>().first { it.key.decodeToString() == "user:1001" }
        assertEquals(
            "Ada",
            ada.fields
                .first { it.first.decodeToString() == "name" }
                .second
                .decodeToString(),
        )
        assertEquals(
            "pro",
            ada.fields
                .first { it.first.decodeToString() == "plan" }
                .second
                .decodeToString(),
        )
        assertTrue(entries.any { it is SortedSetEntry && it.key.decodeToString() == "board:2026-09" })
    }

    @Test
    fun `a sorted set of more than a thousand members is written in chunks`() {
        val commands = StringBuilder()
        val script = LoadScript({ bytes, length -> commands.append(bytes.decodeToString(0, length)) })
        script.write(
            SortedSetEntry(
                Part.LEADERBOARDS,
                "b".encodeToByteArray(),
                List(2500) {
                    "u$it".encodeToByteArray()
                },
                LongArray(2500),
            ),
        )
        script.finish()
        assertEquals(
            listOf(2002, 2002, 1002),
            Regex("""\*(\d+)\r\n""")
                .findAll(commands)
                .map {
                    it.groupValues[1].toInt()
                }.toList(),
        )
    }

    private companion object {
        val PINNED = -5754114991008305168L to 2628554L
    }
}
