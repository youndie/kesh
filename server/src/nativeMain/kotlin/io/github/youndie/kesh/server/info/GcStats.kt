package io.github.youndie.kesh.server.info

import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

/**
 * The collector's collections for `/metrics` (B-31): each exported once, by its epoch, from what the
 * runtime keeps of the last finished one — `GC.lastGCInfo`, `@NativeRuntimeApi` and experimental.
 *
 * **One epoch late.** The runtime keeps a finished epoch as its current one until the next starts, and
 * `lastGCInfo` reads the one before (`JetBrains/kotlin@v2.4.20!/kotlin-native/runtime/src/gc/common/cpp/GCStatistics.cpp`,
 * `last = current`). A poll sees a collection once the next has begun, and misses one only when two
 * begin between two polls; [missed] counts those, and the epochs before the first poll.
 *
 * Store thread only: [poll] runs in the periodic work.
 */
class GcStats(
    private val read: () -> Collection? = ::lastFinished,
) {
    /** One collection, in whole microseconds, as the runtime's log reports it. */
    class Collection(
        val epoch: Long,
        /** "Mutators pause time #1": from the request to suspend to the resumption. */
        val firstPauseMicros: Long,
        /** "Mutators pause time #2"; `null` for a collection with no second pause. */
        val secondPauseMicros: Long?,
        val durationMicros: Long,
        val markedObjects: Long,
    )

    /** What `/metrics` renders, copied on the store thread. */
    class Snapshot(
        val firstPause: CommandStats.Command,
        val secondPause: CommandStats.Command,
        val duration: CommandStats.Command,
        val collections: Long,
        val missed: Long,
        val markedObjects: Long,
    )

    private val firstPause = CommandStats.Command()
    private val secondPause = CommandStats.Command()
    private val duration = CommandStats.Command()
    private var collections = 0L
    private var missed = 0L
    private var markedObjects = 0L
    private var lastEpoch = 0L

    /** Exports the last finished collection if it is new, and returns it; `null` if there is none new. */
    fun poll(): Collection? {
        val c = read() ?: return null
        if (c.epoch <= lastEpoch) return null
        missed += c.epoch - lastEpoch - 1
        lastEpoch = c.epoch
        collections++
        firstPause.record(c.firstPauseMicros)
        c.secondPauseMicros?.let(secondPause::record)
        duration.record(c.durationMicros)
        markedObjects = c.markedObjects
        return c
    }

    fun snapshot(): Snapshot =
        Snapshot(firstPause.copy(), secondPause.copy(), duration.copy(), collections, missed, markedObjects)

    companion object {
        /** The runtime's last finished collection, as its log reports it; `null` before the first. */
        @OptIn(NativeRuntimeApi::class, ExperimentalStdlibApi::class)
        fun lastFinished(): Collection? {
            val info = GC.lastGCInfo ?: return null
            val second =
                info.secondPauseEndTimeNs?.let { end ->
                    info.secondPauseRequestTimeNs?.let { request -> (end - request) / 1000 }
                }
            return Collection(
                epoch = info.epoch,
                firstPauseMicros = (info.firstPauseEndTimeNs - info.firstPauseRequestTimeNs) / 1000,
                secondPauseMicros = second,
                durationMicros = (info.endTimeNs - info.startTimeNs) / 1000,
                markedObjects = info.markedCount,
            )
        }
    }
}
