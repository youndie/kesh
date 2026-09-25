package io.github.youndie.kesh.server.persistence

import io.github.youndie.kesh.resp.Reply
import io.github.youndie.kesh.store.Db

/**
 * `SAVE` and `LASTSAVE` (B-14). `SAVE` runs on the store thread and holds it for the whole write, as
 * Redis's `SAVE` holds its event loop: the snapshot is the dataset at the moment it began. A failure
 * answers `-ERR` — Redis's `shared.err`, no more — and the reason goes to the log.
 */
class Persistence(
    private val file: SnapshotFile,
    /** Milliseconds since the epoch. */
    private val clock: () -> Long,
    /** Keys the snapshot held at startup — `rdb_last_load_keys_loaded`. */
    val loadedKeys: Long = 0,
) {
    /** `server.lastsave`: seconds since the epoch of the last successful save, the start until then. */
    var lastSave: Long = clock() / 1000
        private set

    /** Saves that succeeded — `rdb_saves`. */
    var saves: Long = 0
        private set

    fun save(db: Db): Reply {
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
}
