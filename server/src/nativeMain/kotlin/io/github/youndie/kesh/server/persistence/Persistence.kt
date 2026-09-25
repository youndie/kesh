package io.github.youndie.kesh.server.persistence

import io.github.youndie.kesh.resp.Reply
import io.github.youndie.kesh.store.Db
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.SIGKILL
import platform.posix.WNOHANG
import platform.posix._exit
import platform.posix.close
import platform.posix.errno
import platform.posix.fork
import platform.posix.kill
import platform.posix.strerror
import platform.posix.waitpid
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.time.TimeSource

/**
 * `SAVE`, `BGSAVE` and `LASTSAVE` (B-14, B-25). `SAVE` runs on the store thread and holds it for the
 * whole write, as Redis's `SAVE` holds its event loop: the snapshot is the dataset at the moment it
 * began. `BGSAVE` forks, as Redis's does, and the child writes the dataset as it was at the fork while
 * the parent serves (research R-6). A failure answers `-ERR` — Redis's `shared.err`, no more — and the
 * reason goes to the log.
 *
 * Every member runs on the store thread: the fork is taken there, and [reap] is called from the
 * periodic work, never from a `SIGCHLD` handler (research R-3).
 */
@OptIn(ExperimentalForeignApi::class)
class Persistence(
    private val file: SnapshotFile,
    /** Milliseconds since the epoch. */
    private val clock: () -> Long,
    /** Keys the snapshot held at startup — `rdb_last_load_keys_loaded`. */
    val loadedKeys: Long = 0,
    /** Descriptors the child closes first: the listeners, so a child outliving its parent holds no port. */
    private val inheritedListeners: () -> List<Int> = { emptyList() },
) {
    /** `server.lastsave`: seconds since the epoch of the last successful save, the start until then. */
    var lastSave: Long = clock() / 1000
        private set

    /** Saves started — `rdb_saves`: a `SAVE` that succeeded, a `BGSAVE` that forked. */
    var saves: Long = 0
        private set

    /** The background save's child, 0 when there is none. */
    private var child = 0
    private var childStarted = TimeSource.Monotonic.markNow()
    private var childStartedEpochSeconds = 0L

    /** `rdb_bgsave_in_progress`. */
    val backgroundSaveInProgress: Boolean get() = child != 0

    /** `rdb_last_bgsave_status`: false once a background save failed, until one succeeds. */
    var lastBackgroundSaveOk: Boolean = true
        private set

    /** `rdb_last_bgsave_time_sec`: how long the last background save took, -1 before the first. */
    var lastBackgroundSaveSeconds: Long = -1
        private set

    /** `rdb_current_bgsave_time_sec`: how long the running one has taken, -1 when none runs. */
    val currentBackgroundSaveSeconds: Long
        get() = if (child == 0) -1 else clock() / 1000 - childStartedEpochSeconds

    fun save(db: Db): Reply {
        if (child != 0) return Reply.Error(ALREADY_IN_PROGRESS)
        db.now = clock()
        return try {
            val outcome = file.save(db)
            lastSave = clock() / 1000
            saves++
            println("kesh: saved ${outcome.keys} keys, ${outcome.bytes} bytes, to ${file.path} in ${outcome.millis} ms")
            Reply.OK
        } catch (e: SnapshotIOException) {
            println("kesh: save failed: ${e.message}")
            Reply.Error("ERR")
        }
    }

    /**
     * `BGSAVE [SCHEDULE]`. kesh has no other kind of child, so `SCHEDULE` changes nothing: Redis's
     * reply to it differs only while an AOF rewrite runs.
     */
    fun backgroundSave(
        db: Db,
        arguments: List<ByteArray>,
    ): Reply {
        if (arguments.size > 1 || arguments.any { !it.decodeToString().equals("schedule", ignoreCase = true) }) {
            return Reply.Error("ERR syntax error")
        }
        if (child != 0) return Reply.Error(ALREADY_IN_PROGRESS)
        db.now = clock()
        saves++
        val forkMark = TimeSource.Monotonic.markNow()
        val pid = fork()
        if (pid == 0) inChild(db)
        if (pid < 0) {
            lastBackgroundSaveOk = false
            println("kesh: can't save in background: fork: ${strerror(errno)?.toKString()}")
            return Reply.Error("ERR")
        }
        child = pid
        childStarted = TimeSource.Monotonic.markNow()
        childStartedEpochSeconds = clock() / 1000
        println(
            "kesh: background saving started by pid $pid; fork() took ${forkMark.elapsedNow().inWholeMicroseconds} µs",
        )
        return Reply.Simple("Background saving started")
    }

    /**
     * The child: one thread — the store thread's copy — in a runtime whose collector thread did not
     * survive the fork. Its first mutator assist would wait for a collection that never comes (research
     * R-6), so a finite `GC.maxHeapBytes` turns the assists off before anything allocates; the heap then
     * grows by what the writer allocates, and is gone at `_exit`. It never returns: returning would
     * resume the parent's event loop in a second process. The parent reports the outcome; the child prints
     * only its own failure's reason, which the exit status cannot carry (`println` is a `write`, not stdio).
     */
    @OptIn(NativeRuntimeApi::class)
    private fun inChild(db: Db): Nothing {
        var status = 1
        try {
            GC.maxHeapBytes = Long.MAX_VALUE - 1
            inheritedListeners().forEach { if (it >= 0) close(it) }
            file.save(db)
            status = 0
        } catch (e: Throwable) {
            println("kesh: background saving child failed: $e")
        }
        _exit(status)
        error("unreachable")
    }

    /**
     * Collects a finished child, if there is one: `waitpid(WNOHANG)` from the periodic work. Success
     * moves `LASTSAVE` to the time it ended, as Redis's `backgroundSaveDoneHandlerDisk` does; a failure
     * or a signal leaves it and marks the status `err`, and a killed child's temporary file is removed.
     */
    fun reap() {
        if (child == 0) return
        memScoped {
            val status = alloc<IntVar>()
            if (waitpid(child, status.ptr, WNOHANG) != child) return
            val signal = status.value and 0x7f
            val exitCode = (status.value shr 8) and 0xff
            val millis = childStarted.elapsedNow().inWholeMilliseconds
            lastBackgroundSaveSeconds = millis / 1000
            when {
                signal == 0 && exitCode == 0 -> {
                    lastSave = clock() / 1000
                    lastBackgroundSaveOk = true
                    println("kesh: background saving terminated with success in $millis ms")
                }

                signal == 0 -> {
                    lastBackgroundSaveOk = false
                    println("kesh: background saving error: the child exited with $exitCode after $millis ms")
                }

                else -> {
                    lastBackgroundSaveOk = false
                    file.removeTemporaryOf(child)
                    println("kesh: background saving terminated by signal $signal after $millis ms")
                }
            }
            child = 0
        }
    }

    /**
     * Kills a running child and waits for it — before a stop's own save, which it would otherwise race
     * to the rename, as Redis's `prepareForShutdown` does.
     */
    fun killChild() {
        if (child == 0) return
        kill(child, SIGKILL)
        memScoped {
            val status = alloc<IntVar>()
            waitpid(child, status.ptr, 0)
        }
        file.removeTemporaryOf(child)
        println("kesh: killed the background saving child $child")
        child = 0
    }

    private companion object {
        /** `rdb.c`'s `saveCommand` and `bgsaveCommand`, Redis 7.2. */
        const val ALREADY_IN_PROGRESS = "ERR Background save already in progress"
    }
}
