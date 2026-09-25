package io.github.youndie.kesh.conformance

import io.github.youndie.kompot.realtime.redis.RedisKompotUpdateBus
import io.github.youndie.kompot.realtime.server.KompotBusMessage
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/**
 * `conformance --kesh HOST:PORT --oracle HOST:PORT --kesh-locked HOST:PORT --oracle-locked HOST:PORT
 * --scripts DIR`
 *
 * The "locked" pair runs with `requirepass conformance-secret`; scripts whose header says
 * `# requires: password` run against it, unauthenticated, and send `AUTH` themselves. The rest run
 * against the open pair. Prints the oracle's version first, every disagreement
 * with both byte streams, the count of comparisons per kind, and exits 1 on any disagreement.
 * `conformance/run.sh` starts the four servers and calls this.
 */
fun main(args: Array<String>) {
    val options = args.toList().chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    val open = Runner(Endpoint.parse(options.getValue("kesh")), Endpoint.parse(options.getValue("oracle")))
    val locked =
        Runner(
            Endpoint.parse(options.getValue("kesh-locked")),
            Endpoint.parse(options.getValue("oracle-locked")),
        )

    println("oracle: redis_version ${oracleVersion(Endpoint.parse(options.getValue("oracle")))}")

    val scripts =
        File(options.getValue("scripts"))
            .walkTopDown()
            .filter { it.isFile && it.extension == "redis" }
            .sortedBy { it.path }
            .map(Script::load)
            .toList()
    check(scripts.isNotEmpty()) {
        "no scripts under ${options.getValue("scripts")} — a run that compares nothing is not a pass"
    }

    val outcomes = scripts.flatMap { (if (it.requiresPassword) locked else open).run(it) }
    outcomes.filterNot { it.agree }.forEach { println(Runner.describe(it)) }

    val byKind =
        outcomes
            .groupingBy {
                if (it.step.kind ==
                    Script.Kind.RAW
                ) {
                    "raw"
                } else {
                    it.step.normaliser.name
                        .lowercase()
                }
            }.eachCount()
    val failed = outcomes.count { !it.agree }
    println(
        "${scripts.size} scripts, ${outcomes.size} comparisons: " +
            byKind.entries.joinToString(", ") { "${it.value} ${it.key}" },
    )
    println(if (failed == 0) "all agree" else "$failed disagree")

    val lettuce = lettuceSmoke(Endpoint.parse(options.getValue("kesh")))
    println("lettuce ${RedisClient::class.java.`package`.implementationVersion}: $lettuce")

    val kompot = kompotBus(Endpoint.parse(options.getValue("kesh")))
    println("kompot bus: $kompot")

    exitProcess(if (failed == 0 && lettuce == "PONG" && kompot == DELIVERED) 0 else 1)
}

private const val DELIVERED = "delivered from one instance to the other"

/**
 * kesh's first consumer, unchanged (B-27, research D-26): two instances of kompot's
 * `RedisKompotUpdateBus`, each on its own Lettuce client — two processes, as far as kesh can tell —
 * with one channel prefix. What one publishes, the other's `PSUBSCRIBE` must receive.
 */
private fun kompotBus(kesh: Endpoint): String {
    val url = "redis://${kesh.host}:${kesh.port}"
    val prefix = "conformance:${System.nanoTime()}"
    val a = RedisKompotUpdateBus(RedisClient.create(url), channelPrefix = prefix)
    val b = RedisKompotUpdateBus(RedisClient.create(url), channelPrefix = prefix)
    return try {
        runBlocking {
            val received = CompletableDeferred<KompotBusMessage>()
            val listening = launch(Dispatchers.Default) { b.messages().collect { received.complete(it) } }
            // Subscribing is asynchronous: a message published before the server took the
            // PSUBSCRIBE goes past, as it would in Redis. Published until one arrives.
            val message =
                withTimeout(10.seconds) {
                    while (!received.isCompleted) {
                        a.publish("home:user1", "payload")
                        delay(200)
                    }
                    received.await()
                }
            listening.cancel()
            if (message.topic == "home:user1" && message.payload == "payload") DELIVERED else "received $message"
        }
    } catch (e: Exception) {
        "failed: $e"
    } finally {
        a.close()
        b.close()
    }
}

/** `redis_version` from the oracle's `INFO server`, so every run names what it was compared with. */
private fun oracleVersion(oracle: Endpoint): String {
    val step = Script.parse("info", "INFO server").steps.single()
    java.net.Socket(oracle.host, oracle.port).use { socket ->
        socket.getOutputStream().write(step.bytes)
        val frame = RespFrame.read(socket.getInputStream().buffered())
        val text = frame.bytes.decodeToString()
        return Regex("redis_version:(\\S+)").find(text)?.groupValues?.get(1) ?: "unknown ($text)"
    }
}

/**
 * An unchanged client connects: Lettuce with its defaults opens with `HELLO 3`, gets `NOPROTO`, falls
 * back to RESP2 (research §1.1), and `SET`, `GET` and `PING` work — the "Lettuce connects unchanged"
 * scenario of `feature-resp-connection`.
 */
private fun lettuceSmoke(kesh: Endpoint): String {
    val client = RedisClient.create(RedisURI.create(kesh.host, kesh.port))
    return try {
        client.connect().use {
            val commands = it.sync()
            commands.set("lettuce:smoke", "Ada")
            val read = commands.get("lettuce:smoke")
            commands.del("lettuce:smoke")
            if (read == "Ada") commands.ping() else "SET then GET read back $read"
        }
    } catch (e: Exception) {
        "failed: $e"
    } finally {
        client.shutdown()
    }
}
