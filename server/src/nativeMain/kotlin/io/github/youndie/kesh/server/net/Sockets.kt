package io.github.youndie.kesh.server.net

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.linux.RLIMIT_NOFILE
import platform.linux.getrlimit
import platform.linux.inet_ntop
import platform.linux.rlimit
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.AF_UNSPEC
import platform.posix.AI_PASSIVE
import platform.posix.FD_CLOEXEC
import platform.posix.F_GETFL
import platform.posix.F_SETFD
import platform.posix.F_SETFL
import platform.posix.INET6_ADDRSTRLEN
import platform.posix.IPPROTO_TCP
import platform.posix.O_NONBLOCK
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.TCP_NODELAY
import platform.posix.accept
import platform.posix.addrinfo
import platform.posix.bind
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.freeaddrinfo
import platform.posix.gai_strerror
import platform.posix.getaddrinfo
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.ntohs
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6
import platform.posix.sockaddr_storage
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.strerror

/** The socket calls kesh's transport needs (research D-31), as Redis's `anet.c` wraps them. */
@OptIn(ExperimentalForeignApi::class)
object Sockets {
    class Refused(
        message: String,
    ) : Exception(message)

    /**
     * A non-blocking listening socket on [host]:[port] (0 for any port), with `SO_REUSEADDR` as Redis
     * sets it (`anetSetReuseAddr`): a restart inside `TIME_WAIT` must not fail. Throws [Refused] with
     * the reason, `strerror`'s words ("Address already in use").
     */
    fun listen(
        host: String,
        port: Int,
    ): Int =
        memScoped {
            val hints = alloc<addrinfo>()
            hints.ai_family = AF_UNSPEC
            hints.ai_socktype = SOCK_STREAM
            hints.ai_flags = AI_PASSIVE
            val result = alloc<CPointerVar<addrinfo>>()
            val rc = getaddrinfo(host, port.toString(), hints.ptr, result.ptr)
            if (rc != 0) throw Refused(gai_strerror(rc)?.toKString() ?: "getaddrinfo $rc")
            try {
                val info = result.value!!.pointed
                val fd = socket(info.ai_family, SOCK_STREAM, 0)
                if (fd < 0) throw Refused(reason())
                val one = alloc<IntVar>().apply { value = 1 }
                setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
                if (bind(fd, info.ai_addr, info.ai_addrlen) != 0 || listen(fd, BACKLOG) != 0) {
                    val why = reason()
                    close(fd)
                    throw Refused(why)
                }
                nonBlocking(fd)
                fd
            } finally {
                freeaddrinfo(result.value)
            }
        }

    /** An accepted connection, non-blocking, `TCP_NODELAY` as Redis sets it; `null` when none is waiting. */
    fun accept(listener: Int): Int? {
        val fd = accept(listener, null, null)
        if (fd < 0) return null
        nonBlocking(fd)
        memScoped {
            val one = alloc<IntVar>().apply { value = 1 }
            setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, one.ptr, sizeOf<IntVar>().convert())
        }
        return fd
    }

    /** The port [fd] is bound to. */
    fun port(fd: Int): Int = address(fd, local = true).substringAfterLast(':').toInt()

    /** `ip:port` of [fd]'s own end, or of its peer. */
    fun address(
        fd: Int,
        local: Boolean,
    ): String =
        memScoped {
            val storage = alloc<sockaddr_storage>()
            val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_storage>().convert() }
            val sa = storage.ptr.reinterpret<sockaddr>()
            val rc = if (local) getsockname(fd, sa, length.ptr) else platform.posix.getpeername(fd, sa, length.ptr)
            if (rc != 0) return@memScoped "?:0"
            val text = allocArray<ByteVar>(INET6_ADDRSTRLEN)
            when (storage.ss_family.toInt()) {
                AF_INET -> {
                    val v4 = storage.ptr.reinterpret<sockaddr_in>().pointed
                    inet_ntop(AF_INET, v4.sin_addr.ptr, text, INET6_ADDRSTRLEN.convert())
                    "${text.toKString()}:${ntohs(v4.sin_port)}"
                }

                AF_INET6 -> {
                    val v6 = storage.ptr.reinterpret<sockaddr_in6>().pointed
                    inet_ntop(AF_INET6, v6.sin6_addr.ptr, text, INET6_ADDRSTRLEN.convert())
                    "${text.toKString()}:${ntohs(v6.sin6_port)}"
                }

                else -> {
                    "?:0"
                }
            }
        }

    /** The soft limit on open descriptors — what `maxclients` is fitted under, as Redis fits it. */
    fun descriptorLimit(): Long =
        memScoped {
            val limit = alloc<rlimit>()
            if (getrlimit(RLIMIT_NOFILE, limit.ptr) != 0) 1024 else limit.rlim_cur.toLong()
        }

    fun reason(): String = strerror(errno)?.toKString() ?: "errno $errno"

    private fun nonBlocking(fd: Int) {
        fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) or O_NONBLOCK)
        fcntl(fd, F_SETFD, FD_CLOEXEC)
    }

    /** `listen`'s backlog: Redis's `tcp-backlog` default. */
    private const val BACKLOG = 511
}
