package io.github.youndie.kesh.server.client

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.closedir
import platform.posix.opendir
import platform.posix.readdir

/**
 * How many connections the transport can watch (research §1.4, D-13).
 *
 * `ktor-network` on Kotlin/Native multiplexes with `pselect` over glibc's `fd_set`, and refuses a
 * descriptor at or above `FD_SETSIZE` with an exception inside its selector. Descriptors are handed
 * out lowest-free, so keeping the number of open descriptors under the ceiling keeps every one of
 * them under it too.
 */
object DescriptorCeiling {
    /** glibc's `FD_SETSIZE`, compiled into `fd_set`; not a setting. */
    const val FD_SETSIZE: Int = 1024

    /**
     * Descriptors kept free for what is not a RESP connection: the connection being refused (it holds
     * a descriptor while it is told so), the HTTP listener and its selector's wakeup pipe, probe and
     * metrics connections, and the snapshot file and its temporary twin — with a margin.
     */
    const val RESERVED: Int = 32

    /** The largest `maxclients` that keeps every descriptor under [FD_SETSIZE], given what is open now. */
    fun connectionCeiling(openNow: Int = openDescriptors()): Int = FD_SETSIZE - openNow - RESERVED

    /** Entries of `/proc/self/fd`, not counting the directory stream this function opens to read it. */
    @OptIn(ExperimentalForeignApi::class)
    fun openDescriptors(): Int {
        val dir = opendir("/proc/self/fd") ?: error("cannot read /proc/self/fd")
        var entries = 0
        while (readdir(dir) != null) entries++
        closedir(dir)
        // ".", "..", and the descriptor of the stream itself.
        return entries - 3
    }
}
