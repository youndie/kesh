package io.github.youndie.kesh.server.command

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.timespec

/**
 * Wall-clock milliseconds since the epoch, as Redis's `mstime()`: expiry times are absolute
 * (`EXPIREAT`, `PEXPIRETIME`), so they are read against the real clock, not a monotonic one.
 */
@OptIn(ExperimentalForeignApi::class)
fun epochMillis(): Long =
    memScoped {
        val now = alloc<timespec>()
        clock_gettime(CLOCK_REALTIME, now.ptr)
        now.tv_sec * 1000 + now.tv_nsec / 1_000_000
    }
