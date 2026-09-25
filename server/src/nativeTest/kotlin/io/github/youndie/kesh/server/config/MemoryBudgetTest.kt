package io.github.youndie.kesh.server.config

import io.github.youndie.kesh.resp.encode
import io.github.youndie.kesh.server.KeshServer
import io.github.youndie.kesh.server.ServerConfig
import io.github.youndie.kesh.server.StartupFailure
import io.github.youndie.kesh.server.client.Clients
import io.github.youndie.kesh.server.command.CommandDispatcher
import io.github.youndie.kore.runtime.MemoryBudget
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `maxmemory` held against the container's memory limit (B-26). */
class MemoryBudgetTest {
    private val gib = 1L shl 30
    private val oneGib = MemoryBudget.Bounded(gib, "/sys/fs/cgroup/memory.max")

    @Test
    fun `a maxmemory whose resident peak exceeds the limit is refused naming both numbers`() {
        val check = MemoryBudgetCheck(oneGib, peakRatioTenths = 33)
        val reason = assertNotNull(check.refusal(2 * gib))
        assertTrue("2147483648" in reason && "1073741824" in reason && "/sys/fs/cgroup/memory.max" in reason, reason)
        assertTrue("3.3" in reason, reason)
    }

    @Test
    fun `the largest maxmemory that fits is the limit over the ratio and it fits`() {
        val check = MemoryBudgetCheck(oneGib, peakRatioTenths = 33)
        val largest = assertNotNull(check.largestFitting)
        assertEquals(gib / 33 * 10, largest)
        assertNull(check.refusal(largest))
        assertNotNull(check.refusal(largest + 1))
    }

    @Test
    fun `no limit or an unreadable one or maxmemory 0 refuse nothing`() {
        assertNull(MemoryBudgetCheck(oneGib, 33).refusal(0))
        assertNull(MemoryBudgetCheck(MemoryBudget.Unbounded("/sys/fs/cgroup/memory.max"), 33).refusal(Long.MAX_VALUE))
        assertNull(MemoryBudgetCheck(MemoryBudget.Unavailable("no cgroup"), 33).refusal(Long.MAX_VALUE))
    }

    @Test
    fun `CONFIG SET maxmemory beyond the container is refused and maxmemory stays`() {
        val clients = Clients(maxClients = 10, passwordRequired = false)
        val dispatcher = CommandDispatcher(clients, null, memoryBudget = MemoryBudgetCheck(oneGib, 33))
        val me = clients.register("127.0.0.1:5000", "127.0.0.1:6379") {}!!

        fun reply(vararg args: String) =
            dispatcher.execute(me, args.map { it.encodeToByteArray() })?.encode()?.decodeToString()
        assertEquals("+OK\r\n", reply("CONFIG", "SET", "maxmemory", "100mb"))
        val refused = reply("CONFIG", "SET", "maxmemory", "2gb")!!
        assertTrue(
            refused.startsWith(
                "-ERR CONFIG SET failed (possibly related to argument 'maxmemory') - maxmemory 2147483648 ",
            ),
            refused,
        )
        assertEquals("*2\r\n$9\r\nmaxmemory\r\n$9\r\n104857600\r\n", reply("CONFIG", "GET", "maxmemory"))
    }

    @Test
    fun `a start with a maxmemory beyond the container fails and says why`() {
        val server = KeshServer(ServerConfig(host = "127.0.0.1", port = 0, maxMemory = 2 * gib)) { oneGib }
        val failure = assertFailsWith<StartupFailure> { runBlocking { server.start() } }
        assertTrue(failure.message!!.startsWith("KESH_MAXMEMORY: maxmemory 2147483648 "), failure.message)
        server.close()
    }
}
