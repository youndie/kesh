package io.github.youndie.kesh.server.info

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.getpid
import platform.posix.readlink
import platform.posix.uname
import platform.posix.utsname

/**
 * What the process knows about itself from the kernel: resident memory and threads, read from
 * `/proc/self/status` each time they are asked for, and what does not change — the pid, the
 * executable, `uname`. Resident memory beside `used_memory` is what research R-2 watches.
 */
@OptIn(ExperimentalForeignApi::class)
object ProcessFacts {
    val pid: Long = getpid().toLong()

    /** `uname`'s system, release and machine, as Redis prints them in `os`. */
    val os: String =
        memScoped {
            val name = alloc<utsname>()
            if (uname(name.ptr) !=
                0
            ) {
                "unknown"
            } else {
                "${name.sysname.toKString()} ${name.release.toKString()} ${name.machine.toKString()}"
            }
        }

    /** `/proc/self/exe`, as Redis's `executable`; empty if it cannot be read. */
    val executable: String =
        memScoped {
            val buffer = allocArray<ByteVar>(4096)
            val n = readlink("/proc/self/exe", buffer, 4095.toULong())
            if (n <= 0) "" else buffer.toKString().take(n.toInt())
        }

    /** Resident memory in bytes — `VmRSS`; 0 if it cannot be read. */
    fun residentBytes(): Long = status("VmRSS")?.let { it * 1024 } ?: 0

    /** Threads of the process — `Threads`; 0 if it cannot be read. */
    fun threads(): Long = status("Threads") ?: 0

    /** The number at the start of a `/proc/self/status` line, `VmRSS:  1234 kB` → 1234. */
    private fun status(field: String): Long? {
        val file = fopen("/proc/self/status", "r") ?: return null
        try {
            memScoped {
                val line = allocArray<ByteVar>(256)
                while (fgets(line, 256, file) != null) {
                    val text = line.toKString()
                    if (text.startsWith("$field:")) {
                        return text
                            .substringAfter(':')
                            .trim()
                            .takeWhile { it.isDigit() }
                            .toLongOrNull()
                    }
                }
            }
        } finally {
            fclose(file)
        }
        return null
    }
}
