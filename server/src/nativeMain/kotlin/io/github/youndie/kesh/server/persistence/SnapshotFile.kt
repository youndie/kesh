package io.github.youndie.kesh.server.persistence

import io.github.youndie.kesh.snapshot.Snapshot
import io.github.youndie.kesh.store.Db
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.EINTR
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.fsync
import platform.posix.getpid
import platform.posix.open
import platform.posix.read
import platform.posix.rename
import platform.posix.strerror
import platform.posix.unlink
import platform.posix.write
import kotlin.time.TimeSource

/**
 * The snapshot on disk (B-14): `SAVE` writes [Snapshot]'s bytes to a temporary file in the same
 * directory, `fsync`s it, renames it over [path] and `fsync`s the directory — Redis's `rdbSave`
 * order. A process killed at any point leaves either the old snapshot or the new one, never a torn
 * one under the real name; a torn temporary file is only ever a temporary file.
 */
@OptIn(ExperimentalForeignApi::class)
class SnapshotFile(
    private val directory: String,
    private val name: String,
) {
    val path: String get() = "$directory/$name"

    /** What one save or load did, for the log and for research R-5's numbers. */
    class Outcome(
        val keys: Long,
        val bytes: Long,
        val millis: Long,
    )

    /** Writes [db] as of [Db.now]; throws [SnapshotIOException] with the call that failed and why. */
    fun save(db: Db): Outcome {
        val started = TimeSource.Monotonic.markNow()
        val temporary = temporaryOf(getpid())
        val fd = open(temporary, O_WRONLY or O_CREAT or O_TRUNC, 0x1a4) // 0644
        if (fd < 0) throw SnapshotIOException("open $temporary", errno)
        val written =
            try {
                val result =
                    Snapshot.write(db) { bytes, offset, length ->
                        var done = 0
                        bytes.usePinned { pinned ->
                            while (done < length) {
                                val n = write(fd, pinned.addressOf(offset + done), (length - done).convert())
                                if (n < 0) {
                                    if (errno == EINTR) continue
                                    throw SnapshotIOException("write $temporary", errno)
                                }
                                done += n.toInt()
                            }
                        }
                    }
                if (fsync(fd) != 0) throw SnapshotIOException("fsync $temporary", errno)
                result
            } catch (e: Throwable) {
                close(fd)
                unlink(temporary)
                throw e
            }
        close(fd)
        if (rename(temporary, path) != 0) {
            val cause = errno
            unlink(temporary)
            throw SnapshotIOException("rename $temporary to $path", cause)
        }
        syncDirectory()
        return Outcome(written.keys, written.bytes, started.elapsedNow().inWholeMilliseconds)
    }

    /**
     * Loads [path] into [db] at [Db.now], if the file exists: `null` when there is none. A snapshot that
     * is not whole throws [SnapshotException]; the caller must not serve what was read.
     */
    fun load(db: Db): Outcome? {
        val started = TimeSource.Monotonic.markNow()
        val fd = open(path, O_RDONLY)
        if (fd < 0) {
            if (errno == platform.posix.ENOENT) return null
            throw SnapshotIOException("open $path", errno)
        }
        var bytes = 0L
        try {
            val keys =
                Snapshot.read(db) { buffer, offset, length ->
                    var n: Long
                    do {
                        n = buffer.usePinned { read(fd, it.addressOf(offset), length.convert()) }
                    } while (n < 0 && errno == EINTR)
                    if (n < 0) throw SnapshotIOException("read $path", errno)
                    bytes += n
                    if (n == 0L) -1 else n.toInt()
                }
            return Outcome(keys, bytes, started.elapsedNow().inWholeMilliseconds)
        } finally {
            close(fd)
        }
    }

    /** The temporary file a save by process [pid] writes — a background save's child names it by its own. */
    private fun temporaryOf(pid: Int): String = "$directory/temp-$pid.kesh"

    /** Removes what a killed child left half written. */
    fun removeTemporaryOf(pid: Int) {
        unlink(temporaryOf(pid))
    }

    private fun syncDirectory() {
        val fd = open(directory, O_RDONLY)
        if (fd < 0) return
        fsync(fd)
        close(fd)
    }
}

/** A snapshot file operation that failed, naming the call and the reason. */
@OptIn(ExperimentalForeignApi::class)
class SnapshotIOException(
    call: String,
    code: Int,
) : Exception("$call: ${strerror(code)?.toKString()}")
