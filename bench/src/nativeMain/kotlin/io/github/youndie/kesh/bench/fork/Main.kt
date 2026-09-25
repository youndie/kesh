package io.github.youndie.kesh.bench.fork

import io.github.youndie.kesh.bench.DatasetLoader
import io.github.youndie.kesh.bench.ReferenceDataset
import io.github.youndie.kesh.snapshot.ByteSink
import io.github.youndie.kesh.snapshot.Snapshot
import io.github.youndie.kesh.store.Db
import io.github.youndie.kesh.store.commands.StoreCommands
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.O_CREAT
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.SIGKILL
import platform.posix.WNOHANG
import platform.posix._exit
import platform.posix.close
import platform.posix.fflush
import platform.posix.fork
import platform.posix.kill
import platform.posix.open
import platform.posix.stdout
import platform.posix.usleep
import platform.posix.waitpid
import platform.posix.write
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.time.TimeSource

/**
 * The fork probe (B-14, research R-6): can `BGSAVE` be Redis's — a forked child writing the snapshot
 * while the parent serves?
 *
 *     kesh-fork-probe <scale> <directory> [timeout seconds] [assists-off]
 *
 * Builds the reference dataset at <scale> in-process, times a snapshot written by the process itself
 * (what `SAVE` costs), then forks: the child writes the same snapshot with the same Kotlin code and
 * exits with `_exit`; the parent keeps writing keys (as a serving parent would) and polls `waitpid`
 * with `WNOHANG` — no `SIGCHLD` handler (research R-3). It reports how long `fork()` held the parent,
 * whether the child finished, and in how long; a child still running at the timeout is killed and
 * reported as hung. With `assists-off` the child first sets a finite `GC.maxHeapBytes`, which turns
 * the collector's mutator assists off (research §1.2): with no collector thread in a forked child, an
 * assist waits for an epoch that never comes.
 */
@OptIn(ExperimentalForeignApi::class, NativeRuntimeApi::class)
fun main(args: Array<String>) {
    val scale = args.getOrNull(0)?.toDouble() ?: 0.0625
    val directory = args.getOrNull(1) ?: "/tmp"
    val timeoutSeconds = args.getOrNull(2)?.toLong() ?: 120
    val assistsOff = args.getOrNull(3) == "assists-off"
    val db = Db(seed = 1).apply { now = 1 }
    val loadMark = TimeSource.Monotonic.markNow()
    val (keys, _) = DatasetLoader.load(ReferenceDataset(seed = 42, scale = scale), db)
    say("built $keys keys at scale $scale in ${loadMark.elapsedNow().inWholeMilliseconds} ms")

    val inProcess = TimeSource.Monotonic.markNow()
    val written = writeTo("$directory/probe-parent.kesh", db)
    say("in-process snapshot: ${written / 1_048_576} MB in ${inProcess.elapsedNow().inWholeMilliseconds} ms")

    val keysAtFork = db.size
    val forkMark = TimeSource.Monotonic.markNow()
    val pid = fork()
    if (pid == 0) {
        if (assistsOff) GC.maxHeapBytes = Long.MAX_VALUE - 1
        val childMark = TimeSource.Monotonic.markNow()
        val bytes = writeTo("$directory/probe-child.kesh", db)
        say("child: wrote ${bytes / 1_048_576} MB in ${childMark.elapsedNow().inWholeMilliseconds} ms")
        _exit(0)
    }
    val forkMillis = forkMark.elapsedNow().inWholeMilliseconds
    say("fork() held the parent for $forkMillis ms")
    val set = StoreCommands.all.first { it.name == "set" }
    val waitMark = TimeSource.Monotonic.markNow()
    var parentWrites = 0
    memScoped {
        val status = alloc<IntVar>()
        while (true) {
            repeat(
                1_000,
            ) { set.run(db, listOf("SET", "fork:probe:${parentWrites++}", "v").map { it.encodeToByteArray() }) }
            val done = waitpid(pid, status.ptr, WNOHANG)
            if (done == pid) {
                val exitedAfter = waitMark.elapsedNow().inWholeMilliseconds
                say(verify("$directory/probe-child.kesh", keysAtFork))
                say(
                    "child exited with status ${status.value} after $exitedAfter ms; parent wrote $parentWrites keys meanwhile",
                )
                break
            }
            if (waitMark.elapsedNow().inWholeSeconds >= timeoutSeconds) {
                kill(pid, SIGKILL)
                waitpid(pid, status.ptr, 0)
                say(
                    "child HUNG: still running after $timeoutSeconds s, killed; parent wrote $parentWrites keys meanwhile",
                )
                break
            }
            usleep(10_000u)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun say(line: String) {
    println("kesh-fork-probe: $line")
    fflush(stdout)
}

@OptIn(ExperimentalForeignApi::class)
private fun writeTo(
    path: String,
    db: Db,
): Long {
    val fd = open(path, O_WRONLY or O_CREAT or O_TRUNC, 0x1a4)
    check(fd >= 0) { "cannot open $path" }
    val written =
        Snapshot.write(
            db,
            ByteSink { bytes, offset, length ->
                bytes.usePinned { pinned ->
                    var done = 0
                    while (done < length) {
                        val n = write(fd, pinned.addressOf(offset + done), (length - done).convert())
                        check(n >= 0) { "write failed" }
                        done += n.toInt()
                    }
                }
            },
        )
    close(fd)
    return written.bytes
}

/** The child's snapshot read back: whole (the CRC holds) and the dataset as it was at the fork. */
@OptIn(ExperimentalForeignApi::class)
private fun verify(
    path: String,
    keysAtFork: Int,
): String {
    val fd = platform.posix.open(path, platform.posix.O_RDONLY)
    if (fd < 0) return "child snapshot: missing"
    val copy = Db(seed = 2).apply { now = 1 }
    return try {
        val loaded =
            Snapshot.read(copy) { buffer, offset, length ->
                val n = buffer.usePinned { platform.posix.read(fd, it.addressOf(offset), length.convert()) }
                if (n <= 0L) -1 else n.toInt()
            }
        "child snapshot: whole, $loaded keys (the parent had $keysAtFork at the fork)"
    } catch (e: Exception) {
        "child snapshot: NOT whole — ${e.message}"
    } finally {
        close(fd)
    }
}
