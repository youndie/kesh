package io.github.youndie.kesh.server

import io.github.youndie.kore.lifecycle.AnnounceNotReady
import io.github.youndie.kore.lifecycle.ShutdownDeadlines
import io.github.youndie.kore.lifecycle.runUntilSignal
import kotlinx.coroutines.runBlocking
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

@OptIn(NativeRuntimeApi::class)
fun main() {
    // Which build this is (B-30): a measurement names its arm from the process, not only from an md5.
    println("kesh: built with ${BuildInfo.ALLOCATOR_PAGE_SIZE_KB} KiB allocator pages")
    val config = ServerConfig.fromEnvironment()
    // Research R-7: the runtime turns its mutator assists off when the heap ceiling is finite
    // (`GCSchedulerConfig::mutatorAssists`). A ceiling this high caps nothing else.
    if (!config.gcAssists) GC.maxHeapBytes = Long.MAX_VALUE - 1
    // kore refuses a plan that cannot fit the declared grace period — at startup, where someone reads
    // both numbers, not as a SIGKILL in the middle of a save (B-16).
    val deadlines =
        try {
            ShutdownDeadlines(
                drain = config.shutdownDrainSeconds.seconds,
                gracePeriod = config.terminationGraceSeconds?.seconds,
            )
        } catch (e: IllegalArgumentException) {
            println("kesh: ${e.message}")
            exitProcess(1)
        }
    runBlocking {
        val server = KeshServer(config)
        try {
            server.start()
        } catch (e: StartupFailure) {
            println("kesh: ${e.message}")
            exitProcess(1)
        }
        println("kesh: listening on ${config.host}:${server.port}, maxclients ${server.maxClients}")

        config.httpPort?.let { println("kesh: probes and metrics on ${config.host}:${server.httpPort}") }

        // After the listener is serving, as kore requires: a signal that arrived earlier would run a
        // plan with nothing to drain. Readiness goes false first (B-15), then the RESP listener drains
        // and, if configured, saves (B-16).
        runUntilSignal(deadlines, onFinished = { run -> println(run.transcript) }) {
            announce(AnnounceNotReady(server.readiness))
            drain(server)
        }
        server.close()
    }
}
