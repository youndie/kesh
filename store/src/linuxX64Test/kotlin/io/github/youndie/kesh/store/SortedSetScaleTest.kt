package io.github.youndie.kesh.store

import io.github.youndie.kesh.resp.encode
import io.github.youndie.kesh.store.commands.StoreCommands
import io.github.youndie.kesh.store.zsets.ZSetValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

// B-09's scale scenarios, on linuxX64 only — the target that ships, and no JIT warm-up to separate
// from the structure's own cost.
class SortedSetScaleTest {
    private fun board(members: Int): ZSetValue =
        ZSetValue(
            seed = 3,
        ).apply { repeat(members) { put("player:$it".encodeToByteArray(), (it * 7 % members).toDouble()) } }

    @Test
    fun `a million-member board answers its top ten and ranks`() {
        val db = Db().apply { now = 1 }
        db.set("board:2026-09".encodeToByteArray(), board(1_000_000))
        val commands = StoreCommands.all.associateBy { it.name }

        fun r(vararg args: String) =
            commands
                .getValue(
                    args[0].lowercase(),
                ).run(db, args.map { it.encodeToByteArray() })
                .encode()
                .decodeToString()
        val top = r("ZREVRANGE", "board:2026-09", "0", "9", "WITHSCORES").split("\r\n")
        val scores = top.filterIndexed { i, _ -> i > 0 && i % 4 == 0 }.map { it.toDouble() }
        assertEquals((999_999 downTo 999_990).map { it.toDouble() }, scores)
        // Scores are a permutation of 0 until 1 000 000 (7 is coprime to it); 142857 × 7 = 999999.
        assertEquals("\$6\r\n999999\r\n", r("ZSCORE", "board:2026-09", "player:142857"))
        assertEquals(":0\r\n", r("ZREVRANK", "board:2026-09", "player:142857"))
        assertEquals(":999999\r\n", r("ZRANK", "board:2026-09", "player:142857"))
    }

    @Test
    fun `rank on a million members takes within ten times its time on a thousand`() {
        val small = board(1_000)
        val large = board(1_000_000)

        fun perCall(
            zset: ZSetValue,
            members: Int,
        ): Double {
            val probes = (0 until 20_000).map { "player:${it * 7919 % members}".encodeToByteArray() }
            repeat(3) { probes.forEach { zset.rank(it) } }
            val times =
                (0 until 5).map {
                    val mark = TimeSource.Monotonic.markNow()
                    probes.forEach { zset.rank(it) }
                    mark.elapsedNow().inWholeNanoseconds.toDouble() / probes.size
                }
            return times.sorted()[2]
        }
        val a = perCall(small, 1_000)
        val b = perCall(large, 1_000_000)
        println("ZRANK per call: 1k members $a ns, 1M members $b ns, ratio ${b / a}")
        assertTrue(b <= 10 * a, "1M: $b ns, 1k: $a ns")
    }
}
