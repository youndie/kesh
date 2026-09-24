package io.github.youndie.kesh.bench

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.fwrite
import platform.posix.stderr
import platform.posix.stdout
import kotlin.system.exitProcess

/**
 * `kesh-dataset [--seed N] [--scale F] [--out PATH|-] [--summary-only] [--check]`
 *
 * Writes the reference dataset as a RESP command stream to PATH (`-` is stdout, the default) and its
 * summary to stderr. `--summary-only` generates without writing; `--check` exits 1 when a part's user
 * bytes are more than 2 % from appendix A.
 */
@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    var seed = 42L
    var scale = 1.0
    var out = "-"
    var summaryOnly = false
    var check = false
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--seed" -> {
                seed = args[++i].toLong()
            }

            "--scale" -> {
                scale = args[++i].toDouble()
            }

            "--out" -> {
                out = args[++i]
            }

            "--summary-only" -> {
                summaryOnly = true
            }

            "--check" -> {
                check = true
            }

            else -> {
                fputs("unknown argument ${args[i]}\n", stderr)
                exitProcess(2)
            }
        }
        i++
    }

    val file = if (summaryOnly || out == "-") null else fopen(out, "wb") ?: error("cannot open $out")
    val target = file ?: stdout
    val sink =
        ByteSink { bytes, length ->
            if (!summaryOnly) bytes.usePinned { fwrite(it.addressOf(0), 1u, length.toULong(), target) }
        }
    val summary = DatasetSummary(scale)
    val script = LoadScript(sink)
    ReferenceDataset(seed, scale).entries().forEach {
        summary.add(it)
        script.write(it)
    }
    script.finish()
    fflush(target)
    file?.let { fclose(it) }

    fputs("seed $seed, scale $scale\n", stderr)
    fputs(summary.table(), stderr)
    if (check && !summary.withinTolerance()) {
        fputs("user bytes are more than 2 % from appendix A\n", stderr)
        exitProcess(1)
    }
}
