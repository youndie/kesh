package io.github.youndie.kesh.conformance

import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * The kompot check's own negative control (kesh#19): a server that refuses `PSUBSCRIBE` must fail it,
 * and the failure must name the refusal rather than only a timeout. Since kompot 0.40 the bus names it
 * itself — `messages()` fails with the server's error (youndie/kompot#206) — so the check asks nothing
 * of Lettuce directly. Against kesh itself the same control is a mutation, recorded in
 * services/conformance.md; this one runs in CI.
 */
class KompotBusTest {
    private val servers = mutableListOf<ServerSocket>()

    @AfterTest
    fun stop() = servers.forEach { it.close() }

    /**
     * Just enough of a server for Lettuce: `HELLO` is refused as kesh refuses it (Lettuce falls back
     * to RESP2), `PUBLISH` reaches nobody, `PSUBSCRIBE` is refused, anything else is `OK`.
     */
    private fun refusingPsubscribe(): Endpoint {
        val server = ServerSocket(0).also { servers += it }
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    client.use {
                        val input = it.getInputStream().buffered()
                        val output = it.getOutputStream()
                        while (true) {
                            val command = runCatching { RespFrame.read(input) }.getOrNull() ?: break
                            val name =
                                command.elements
                                    ?.firstOrNull()
                                    ?.bulkPayload()
                                    ?.decodeToString()
                                    ?.uppercase()
                            val reply =
                                when (name) {
                                    "HELLO" -> "-NOPROTO unsupported protocol version\r\n"
                                    "PUBLISH" -> ":0\r\n"
                                    "PSUBSCRIBE" -> "-ERR control: PSUBSCRIBE refused\r\n"
                                    else -> "+OK\r\n"
                                }
                            output.write(reply.encodeToByteArray())
                            output.flush()
                        }
                    }
                }
            }
        }
        return Endpoint("127.0.0.1", server.localPort)
    }

    @Test
    fun `a refused PSUBSCRIBE fails the check and is named`() {
        assertEquals(
            "failed: io.lettuce.core.RedisCommandExecutionException: ERR control: PSUBSCRIBE refused",
            kompotBus(refusingPsubscribe(), timeout = 2.seconds),
        )
    }
}
