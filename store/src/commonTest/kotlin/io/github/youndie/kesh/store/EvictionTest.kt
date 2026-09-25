package io.github.youndie.kesh.store

import io.github.youndie.kesh.store.commands.StoreCommands
import io.github.youndie.kesh.store.eviction.Eviction
import io.github.youndie.kesh.store.eviction.EvictionPolicy
import io.github.youndie.kesh.store.keyspace.Keyspace
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which key each policy takes, what it leaves, and when it stops (B-12). The oracle compares the
 * policies at `maxmemory 1`, where every candidate goes; the order a partial eviction takes is only
 * checked here, because the two servers account memory differently.
 */
class EvictionTest {
    private val db = Db(seed = 3).apply { now = 1_000_000_000 }
    private val commands = StoreCommands.all.associateBy { it.name }

    private fun r(vararg args: String) =
        commands.getValue(args[0].lowercase()).run(db, args.map { it.encodeToByteArray() })

    private fun exists(key: String) = db.keyspace.get(key.encodeToByteArray()) != null

    /** A limit that one more key's worth of data crosses: the next eviction has to take exactly one. */
    private fun limitOneKeyBelow() {
        db.maxMemory = db.usedMemory - 1
    }

    @Test
    fun `allkeys-lru takes the key accessed longest ago`() {
        val eviction = Eviction(random = Random(1)).apply { policy = EvictionPolicy.ALLKEYS_LRU }
        for (i in 0 until 10) {
            r("SET", "k$i", "v")
            db.now += 1_000
        }
        // k0 is the oldest write, but a read makes it the newest; k1 is then the oldest.
        r("GET", "k0")
        limitOneKeyBelow()
        eviction.samples = 10
        assertEquals(Eviction.Result.OK, eviction.perform(db))
        assertTrue(!exists("k1"), "the least recently used key goes")
        assertTrue(exists("k0"), "a read counts as an access")
        assertEquals(9, db.size)
        assertEquals(1L, eviction.evictedKeys)
    }

    @Test
    fun `EXISTS and TYPE and TTL do not count as an access`() {
        val eviction = Eviction(random = Random(2)).apply { policy = EvictionPolicy.ALLKEYS_LRU }
        r("SET", "old", "v")
        db.now += 5_000
        r("SET", "new", "v")
        db.now += 5_000
        r("EXISTS", "old")
        r("TYPE", "old")
        r("TTL", "old")
        r("PTTL", "old")
        limitOneKeyBelow()
        eviction.perform(db)
        assertTrue(!exists("old"), "EXISTS, TYPE and the TTL family look with LOOKUP_NOTOUCH")
        assertTrue(exists("new"))
    }

    @Test
    fun `volatile-ttl takes the nearest expiry and never a key without one`() {
        val eviction = Eviction(random = Random(3)).apply { policy = EvictionPolicy.VOLATILE_TTL }
        r("SET", "forever", "v")
        r("SET", "soon", "v", "PX", "50000")
        r("SET", "later", "v", "PX", "90000")
        r("SET", "latest", "v", "PX", "900000")
        limitOneKeyBelow()
        eviction.perform(db)
        assertTrue(!exists("soon"))
        assertTrue(exists("later") && exists("latest") && exists("forever"))
        assertNull(db.expires.get("soon".encodeToByteArray()), "the expiry index loses it too")
    }

    @Test
    fun `volatile-lru ignores keys without an expiry however old`() {
        val eviction = Eviction(random = Random(4)).apply { policy = EvictionPolicy.VOLATILE_LRU }
        r("SET", "ancient", "v")
        db.now += 60_000
        r("SET", "v1", "v", "EX", "100")
        db.now += 1_000
        r("SET", "v2", "v", "EX", "100")
        limitOneKeyBelow()
        eviction.perform(db)
        assertTrue(exists("ancient"))
        assertTrue(!exists("v1") && exists("v2"))
    }

    @Test
    fun `a volatile policy with nothing volatile fails and so does noeviction`() {
        r("SET", "a", "v")
        r("SET", "b", "v")
        db.maxMemory = 1
        for (policy in listOf(
            EvictionPolicy.VOLATILE_LRU,
            EvictionPolicy.VOLATILE_RANDOM,
            EvictionPolicy.VOLATILE_TTL,
        )) {
            val eviction = Eviction().apply { this.policy = policy }
            assertEquals(Eviction.Result.FAIL, eviction.perform(db), policy.configName)
            assertEquals(0L, eviction.evictedKeys)
        }
        assertEquals(Eviction.Result.FAIL, Eviction().perform(db), "noeviction")
        assertEquals(2, db.size)
    }

    @Test
    fun `the random policies evict until under the limit and no further`() {
        for (policy in listOf(EvictionPolicy.ALLKEYS_RANDOM, EvictionPolicy.VOLATILE_RANDOM)) {
            db.clear()
            db.maxMemory = 0
            repeat(200) { r("SET", "k$it", "x".repeat(40), "EX", "1000") }
            val full = db.usedMemory
            db.maxMemory = full * 3 / 4
            val eviction = Eviction(random = Random(5)).apply { this.policy = policy }
            assertEquals(Eviction.Result.OK, eviction.perform(db), policy.configName)
            assertTrue(db.usedMemory <= db.maxMemory, "${policy.configName}: under the limit")
            val oneKey = full / 200 + 1
            assertTrue(db.usedMemory > db.maxMemory - 2 * oneKey, "${policy.configName}: not far under it")
            assertEquals(db.recount(), db.usedMemory)
            assertEquals(200 - db.size, eviction.evictedKeys.toInt())
        }
    }

    @Test
    fun `a write-heavy client under allkeys-lru stays within one command of the limit`() {
        val eviction = Eviction(random = Random(6)).apply { policy = EvictionPolicy.ALLKEYS_LRU }
        db.maxMemory = 256L * 1024
        var worstOver = 0L
        for (i in 0 until 20_000) {
            // `processCommand`: evict first, then run the command.
            assertTrue(eviction.perform(db) != Eviction.Result.FAIL)
            r("SET", "key:$i", "v".repeat(1 + i % 100))
            worstOver = maxOf(worstOver, db.usedMemory - db.maxMemory)
            if (i % 10 == 0) db.now += 1_000
        }
        // One SET adds an entry, its key and a value of at most 100 bytes — and, once in a while, a
        // larger bucket array for a growing table.
        assertTrue(worstOver < 2L * 1024 + 8L * db.keyspace.capacity, "over by $worstOver bytes")
        assertTrue(eviction.evictedKeys > 10_000, "evicted ${eviction.evictedKeys}")
        assertEquals(db.recount(), db.usedMemory)
        assertNotNull(db.keyspace.get("key:19999".encodeToByteArray()), "the newest key stays")
    }

    @Test
    fun `an eviction past its time limit stops every 16 keys and goes on between commands`() {
        var micros = 0L
        val eviction =
            Eviction(clock = { micros.also { micros += 1_000 } }, random = Random(7)).apply {
                policy = EvictionPolicy.ALLKEYS_RANDOM
            }
        repeat(1_000) { r("SET", "k$it", "v") }
        db.maxMemory = 1
        assertEquals(Eviction.Result.RUNNING, eviction.perform(db))
        assertEquals(16L, eviction.evictedKeys, "the limit is checked every 16 keys, as in Redis")
        assertTrue(eviction.running)
        var rounds = 0
        while (eviction.proceed(db)) rounds++
        assertTrue(!eviction.running)
        assertEquals(0, db.size, "the rounds between commands take the rest")
        assertTrue(rounds > 10)
    }

    @Test
    fun `sample draws from the table while it rehashes too`() {
        val table = Keyspace(seed = 1)
        val random = Random(8)
        var n = 0
        while (!table.isRehashing || table.size < 1_000) table.put("k${n++}".encodeToByteArray(), n)
        val during = table.sample(5) { random.nextInt(it) }
        assertEquals(5, during.size)
        during.forEach { assertTrue(table.get(it.key) === it, "a sampled entry is in the table") }
        val seen = HashSet<String>()
        repeat(2_000) { table.sample(5) { random.nextInt(it) }.forEach { e -> seen += e.key.decodeToString() } }
        assertTrue(seen.size > table.size * 9 / 10, "samples reach most of the table: ${seen.size} of ${table.size}")
        val small = Keyspace().apply { repeat(3) { put("x$it".encodeToByteArray(), it) } }
        assertEquals(3, small.sample(5) { 0 }.size, "never more than the table holds")
    }
}
