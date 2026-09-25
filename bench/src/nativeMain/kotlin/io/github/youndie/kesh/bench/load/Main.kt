package io.github.youndie.kesh.bench.load

import io.github.youndie.kesh.bench.ByteSink
import io.github.youndie.kesh.bench.LoadScript
import io.github.youndie.kesh.bench.ReferenceDataset
import io.github.youndie.kesh.bench.Rng
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * `kesh-load --host H --port P [--seed N] [--scale F] (--load | --run) [...]` — the reference load
 * (§5a, B-17), closed-loop: each connection sends a pipeline and waits for its replies.
 *
 * - `--load` streams the reference dataset into the server through RESP and waits for every reply.
 * - `--run --connections C --pipeline D --warmup W --duration S [--raw PATH]` drives the mix of
 *   [Workload] on the dataset's own keys ([KeyCatalogue]) and prints throughput and latency
 *   percentiles per operation; `--raw` writes every histogram's buckets. Latency is taken per
 *   command, from the pipeline's write to that command's reply, on this machine's monotonic clock.
 */
fun main(args: Array<String>) {
    val options = args.toList().windowed(2, 1, partialWindows = true)

    fun value(name: String): String? = options.firstOrNull { it[0] == name }?.getOrNull(1)
    val host = value("--host") ?: "127.0.0.1"
    val port = value("--port")?.toInt() ?: 6379
    val seed = value("--seed")?.toLong() ?: 42L
    val scale = value("--scale")?.toDouble() ?: 1.0
    when {
        "--load" in args -> {
            load(host, port, seed, scale)
        }

        "--run" in args -> {
            run(
                host,
                port,
                seed,
                scale,
                connections = value("--connections")?.toInt() ?: 50,
                pipeline = value("--pipeline")?.toInt() ?: 1,
                warmupSeconds = value("--warmup")?.toInt() ?: 10,
                durationSeconds = value("--duration")?.toInt() ?: 60,
                raw = value("--raw"),
            )
        }

        else -> {
            println(
                "kesh-load --host H --port P [--seed N] [--scale F] (--load | --run [--connections C] [--pipeline D] [--warmup W] [--duration S] [--raw PATH])",
            )
            exitProcess(2)
        }
    }
}

private fun load(
    host: String,
    port: Int,
    seed: Long,
    scale: Double,
) = runBlocking {
    val started = TimeSource.Monotonic.markNow()
    val selector = SelectorManager(Dispatchers.IO)
    val socket = aSocket(selector).tcp().connect(host, port)
    val output = socket.openWriteChannel(autoFlush = false)
    val input = socket.openReadChannel()
    val chunks = Channel<ByteArray>(capacity = 16)
    // The number of commands is known only when the script is done; a PING after the last chunk makes
    // the reply that ends the wait arrive after that number is, whatever the threads do.
    val total = CompletableDeferred<Long>()
    launch(Dispatchers.Default) {
        val script = LoadScript(ByteSink { bytes, length -> chunks.trySendBlocking(bytes.copyOf(length)).getOrThrow() })
        ReferenceDataset(seed, scale).entries().forEach(script::write)
        script.finish()
        total.complete(script.commands)
        chunks.close()
    }
    val reading =
        async {
            val counter = ReplyCounter()
            val buffer = ByteArray(256 * 1024)
            var replies = 0L
            while (!(total.isCompleted && replies >= total.await() + 1)) {
                val n = input.readAvailable(buffer)
                check(n >= 0) { "the server closed after $replies replies" }
                replies += counter.feed(buffer, n)
            }
            replies - 1 to counter.errors
        }
    for (chunk in chunks) {
        output.writeFully(chunk)
        output.flush()
    }
    output.writeFully("*1\r\n$4\r\nPING\r\n".encodeToByteArray())
    output.flush()
    val (replies, errors) = reading.await()
    println("loaded: $replies commands, $errors errors, in ${started.elapsedNow().inWholeMilliseconds} ms")
    socket.close()
    selector.close()
    if (errors > 0) exitProcess(1)
}

private class ConnectionResult(
    val histograms: Map<Workload.Operation, LatencyHistogram>,
    val operations: Long,
    val commands: Long,
    val errors: Long,
)

private fun run(
    host: String,
    port: Int,
    seed: Long,
    scale: Double,
    connections: Int,
    pipeline: Int,
    warmupSeconds: Int,
    durationSeconds: Int,
    raw: String?,
) = runBlocking {
    val keys = KeyCatalogue.of(seed, scale)
    val workload = Workload(keys, seed)
    println(
        "catalogue: ${keys.sessions.size} sessions, ${keys.profiles.size} profiles, ${keys.counters.size} counters, " +
            "${keys.feeds.size} feeds, ${keys.tags.size} tag sets, ${keys.boards.size} boards",
    )
    val selector = SelectorManager(Dispatchers.IO)
    val clock = TimeSource.Monotonic
    val begin = clock.markNow()
    val measureFrom = begin + warmupSeconds.seconds
    val end = measureFrom + durationSeconds.seconds
    val results =
        (0 until connections)
            .map { c ->
                async(Dispatchers.Default) {
                    val rng = Rng(seed * 1_000_003 + c)
                    val socket = aSocket(selector).tcp().connect(host, port)
                    val output = socket.openWriteChannel(autoFlush = false)
                    val input = socket.openReadChannel()
                    val histograms = Workload.Operation.entries.associateWith { LatencyHistogram() }
                    val command = Command()
                    val counter = ReplyCounter()
                    val buffer = ByteArray(256 * 1024)
                    val ops = arrayOfNulls<Workload.Operation>(pipeline)
                    val lastCommandOf = IntArray(pipeline)
                    var operations = 0L
                    var commands = 0L
                    while (!end.hasPassedNow()) {
                        command.clear()
                        var sent = 0
                        for (i in 0 until pipeline) {
                            val op = workload.operation(rng)
                            ops[i] = op
                            sent += workload.write(op, rng, command)
                            lastCommandOf[i] = sent
                        }
                        val t0 = clock.markNow()
                        val measured = measureFrom.hasPassedNow()
                        output.writeFully(command.bytes, 0, command.length)
                        output.flush()
                        var received = 0
                        var next = 0
                        while (received < sent) {
                            val n = input.readAvailable(buffer)
                            check(n >= 0) { "the server closed the connection" }
                            received += counter.feed(buffer, n)
                            val micros = t0.elapsedNow().inWholeMicroseconds
                            while (next < pipeline && lastCommandOf[next] <= received) {
                                if (measured) histograms.getValue(ops[next]!!).record(micros)
                                next++
                            }
                        }
                        if (measured) {
                            operations += pipeline
                            commands += sent
                        }
                    }
                    socket.close()
                    ConnectionResult(histograms, operations, commands, counter.errors)
                }
            }.awaitAll()
    val elapsed = (clock.markNow() - measureFrom).inWholeMilliseconds / 1000.0
    selector.close()

    val merged = Workload.Operation.entries.associateWith { LatencyHistogram() }
    results.forEach { r -> r.histograms.forEach { (op, h) -> merged.getValue(op).add(h) } }
    val all = LatencyHistogram().also { h -> merged.values.forEach(h::add) }
    val operations = results.sumOf { it.operations }
    val commands = results.sumOf { it.commands }
    val errors = results.sumOf { it.errors }
    println("connections $connections, pipeline $pipeline, measured ${elapsed}s after ${warmupSeconds}s of warm-up")
    println(
        "throughput: ${(operations / elapsed).toLong()} operations/s, ${(commands / elapsed).toLong()} commands/s; error replies: $errors",
    )
    println("latency in µs, per operation (from its pipeline's write to its last reply):")
    println("operation  weight  count  p50  p99  p99.9  max")

    fun line(
        name: String,
        weight: String,
        h: LatencyHistogram,
    ) = println(
        "$name  $weight  ${h.count}  ${h.percentile(0.5)}  ${h.percentile(0.99)}  ${h.percentile(0.999)}  ${h.max}",
    )
    Workload.Operation.entries.forEach { line(it.name, "${it.weight}", merged.getValue(it)) }
    line("ALL", "100", all)
    raw?.let { path -> writeRaw(path, merged + (null to all)) }
    if (errors > 0) exitProcess(1)
}

@OptIn(ExperimentalForeignApi::class)
private fun writeRaw(
    path: String,
    histograms: Map<Workload.Operation?, LatencyHistogram>,
) {
    val text =
        buildString {
            for ((op, h) in histograms) {
                append("# ").append(op?.name ?: "ALL").append(" µs-upper-bound count\n")
                append(h.raw())
            }
        }.encodeToByteArray()
    val file = fopen(path, "wb") ?: error("cannot open $path")
    text.usePinned { fwrite(it.addressOf(0), 1u, text.size.toULong(), file) }
    fclose(file)
}
