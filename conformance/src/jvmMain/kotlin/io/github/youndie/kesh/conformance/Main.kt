package io.github.youndie.kesh.conformance

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import java.io.File
import kotlin.system.exitProcess

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
    check(scripts.isNotEmpty()) { "no scripts under ${options.getValue("scripts")} — a run that compares nothing is not a pass" }

    val outcomes = scripts.flatMap { (if (it.requiresPassword) locked else open).run(it) }
    outcomes.filterNot { it.agree }.forEach { println(Runner.describe(it)) }

    val byKind = outcomes.groupingBy { if (it.step.kind == Script.Kind.RAW) "raw" else it.step.normaliser.name.lowercase() }.eachCount()
    val failed = outcomes.count { !it.agree }
    println("${scripts.size} scripts, ${outcomes.size} comparisons: " + byKind.entries.joinToString(", ") { "${it.value} ${it.key}" })
    println(if (failed == 0) "all agree" else "$failed disagree")

    val lettuce = lettuceSmoke(Endpoint.parse(options.getValue("kesh")))
    println("lettuce ${RedisClient::class.java.`package`.implementationVersion}: $lettuce")

    exitProcess(if (failed == 0 && lettuce == "PONG") 0 else 1)
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
 * back to RESP2 (research §1.1), and `PING` works. The first half of the "Lettuce connects unchanged"
 * scenario; `SET` and `GET` join it with B-05.
 */
private fun lettuceSmoke(kesh: Endpoint): String {
    val client = RedisClient.create(RedisURI.create(kesh.host, kesh.port))
    return try {
        client.connect().use { it.sync().ping() }
    } catch (e: Exception) {
        "failed: $e"
    } finally {
        client.shutdown()
    }
}
