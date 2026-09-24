package io.github.youndie.kesh.bench.heap

import io.github.youndie.kesh.bench.ReferenceDataset
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.stdout
import kotlin.time.TimeSource

/**
 * `kesh-heap-probe --encoding naive|packed [--scale F] [--seed N] [--seconds S]`
 *
 * Builds the reference dataset in-process, then runs the write churn for S seconds. Prints marks the
 * reader (`bench/heap-probe/pauses.py`) uses to cut the runtime's GC log to the churn window, and the
 * process's resident memory and threads at each mark. The pauses themselves are in the GC log this
 * binary writes to stderr — it is built with `-Xruntime-logs=gc=info,gcScheduler=info`.
 */
@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    val options = args.toList().chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    val encoding = Encoding.named(options.getValue("encoding"))
    val scale = options["scale"]?.toDouble() ?: 1.0
    val seed = options["seed"]?.toLong() ?: 42L
    val seconds = options["seconds"]?.toLong() ?: 60L
    val start = TimeSource.Monotonic.markNow()

    fun mark(label: String) {
        fputs("PROBE $label at=${start.elapsedNow().inWholeMilliseconds / 1000.0}s ${procStatus()}\n", stdout)
        fflush(stdout)
    }

    mark("start encoding=${encoding.name} scale=$scale seed=$seed")
    val probe = HeapProbe(ReferenceDataset(seed, scale), encoding, seed)
    probe.load()
    mark("loaded keys=${probe.keys}")

    val window = TimeSource.Monotonic.markNow()
    mark("window-start")
    var operations = 0L
    while (window.elapsedNow().inWholeSeconds < seconds) operations += probe.churn(10_000)
    val elapsed = window.elapsedNow().inWholeMilliseconds / 1000.0
    mark("window-end operations=$operations ops_per_s=${(operations / elapsed).toLong()}")
}

/** `VmRSS`, `VmHWM` and `Threads` from `/proc/self/status`. */
private fun procStatus(): String {
    val text = readFile("/proc/self/status")
    return listOf("VmRSS", "VmHWM", "Threads").joinToString(" ") { key ->
        val value = Regex("^$key:\\s+(\\d+)", RegexOption.MULTILINE).find(text)?.groupValues?.get(1) ?: "?"
        "${key.lowercase()}=$value${if (key == "Threads") "" else "kB"}"
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readFile(path: String): String {
    val file = fopen(path, "r") ?: return ""
    val out = StringBuilder()
    memScoped {
        val buffer = allocArray<ByteVar>(4096)
        while (true) {
            val line = fgets(buffer, 4096, file) ?: break
            out.append(line.toKString())
        }
    }
    fclose(file)
    return out.toString()
}
