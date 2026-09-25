package io.github.youndie.kesh.server.net

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import platform.linux.EFD_CLOEXEC
import platform.linux.EFD_NONBLOCK
import platform.linux.EPOLLIN
import platform.linux.EPOLL_CLOEXEC
import platform.linux.EPOLL_CTL_ADD
import platform.linux.EPOLL_CTL_DEL
import platform.linux.EPOLL_CTL_MOD
import platform.linux.epoll_create1
import platform.linux.epoll_ctl
import platform.linux.epoll_event
import platform.linux.epoll_wait
import platform.linux.eventfd
import platform.posix.EINTR
import platform.posix.close
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.read
import platform.posix.write
import kotlin.concurrent.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

/**
 * kesh's store thread and its only I/O thread (research D-31): an `epoll` loop that runs every
 * descriptor's handler and every task given to it, on one thread, one at a time — which is what makes
 * each command atomic without a lock (D-14). Redis's `aeMain` in role.
 *
 * It is also a [CoroutineDispatcher]: what the rest of kesh ran with `withContext(storeThread)` — the
 * periodic work, `/metrics`, the save the drain ends with — is queued here and run between two waits,
 * the loop woken by an `eventfd`. An `EINTR` from a signal is one more turn of the loop (research R-3).
 */
@OptIn(ExperimentalForeignApi::class, ObsoleteWorkersApi::class)
class EventLoop(
    private val threadName: String = "kesh-store",
) : CoroutineDispatcher() {
    /** What a descriptor does when it is ready; called on the loop's thread with `epoll`'s event bits. */
    fun interface Handler {
        fun onEvent(events: UInt)
    }

    private class Task(
        val block: Runnable,
        val next: Task?,
    )

    private val epoll = epoll_create1(EPOLL_CLOEXEC).also { check(it >= 0) { "epoll_create1: errno $errno" } }
    private val wake =
        eventfd(0, EFD_NONBLOCK or EFD_CLOEXEC).also {
            check(it >= 0) { "eventfd: errno $errno" }
        }
    private val handlers = HashMap<Int, Handler>()
    private val tasks = AtomicReference<Task?>(null)
    private val worker = Worker.start(name = threadName)

    @kotlin.concurrent.Volatile
    private var running = true

    init {
        add(wake, EPOLLIN) { drainWake() }
        worker.executeAfter(0L) { loop() }
    }

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        while (true) {
            val head = tasks.value
            if (tasks.compareAndSet(head, Task(block, head))) break
        }
        memScoped {
            val one = alloc<ULongVar>().apply { value = 1u }
            write(wake, one.ptr, 8u)
        }
    }

    /** Watches [fd] for [events] (`EPOLLIN`, `EPOLLOUT`, …). On the loop's thread. */
    fun add(
        fd: Int,
        events: UInt,
        handler: Handler,
    ) {
        handlers[fd] = handler
        control(EPOLL_CTL_ADD, fd, events)
    }

    /** Changes what [fd] is watched for. On the loop's thread. */
    fun modify(
        fd: Int,
        events: UInt,
    ) = control(EPOLL_CTL_MOD, fd, events)

    /** Stops watching [fd]; the caller closes it. On the loop's thread. */
    fun remove(fd: Int) {
        if (handlers.remove(fd) != null) control(EPOLL_CTL_DEL, fd, 0u)
    }

    /** Ends the loop after its current turn and releases the thread. */
    fun close() {
        running = false
        dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable { })
        worker.requestTermination().result
        close(epoll)
        close(wake)
    }

    private fun control(
        op: Int,
        fd: Int,
        events: UInt,
    ) {
        memScoped {
            val event = alloc<epoll_event>()
            event.events = events
            event.data.fd = fd
            check(
                epoll_ctl(epoll, op, fd, event.ptr) == 0 || op == EPOLL_CTL_DEL,
            ) { "epoll_ctl($op, $fd): errno $errno" }
        }
    }

    private fun drainWake() {
        memScoped {
            val count = alloc<ULongVar>()
            read(wake, count.ptr, 8u)
        }
    }

    private fun loop() {
        fopen("/proc/thread-self/comm", "w")?.let {
            fputs(threadName, it)
            fclose(it)
        }
        val events = nativeHeap.allocArray<epoll_event>(MAX_EVENTS)
        try {
            while (running) {
                val n = epoll_wait(epoll, events, MAX_EVENTS, if (tasks.value != null) 0 else -1)
                if (n < 0 && errno != EINTR) error("epoll_wait: errno $errno")
                for (i in 0 until maxOf(n, 0)) {
                    val event = events[i]
                    val fd = event.data.fd
                    handlers[fd]?.let { handler ->
                        try {
                            handler.onEvent(event.events)
                        } catch (e: Throwable) {
                            // One descriptor's failure ends that descriptor, not the process: an
                            // uncaught exception on this thread would take every client with it.
                            println("kesh: handler for descriptor $fd failed: $e")
                        }
                    }
                }
                runTasks()
            }
        } finally {
            nativeHeap.free(events.rawValue)
        }
    }

    private fun runTasks() {
        val taken = tasks.getAndSet(null) ?: return
        // A stack: reversed, so tasks run in the order they were dispatched.
        var reversed: Task? = null
        var t: Task? = taken
        while (t != null) {
            reversed = Task(t.block, reversed)
            t = t.next
        }
        while (reversed != null) {
            try {
                reversed.block.run()
            } catch (e: Throwable) {
                println("kesh: a task on the store thread failed: $e")
            }
            reversed = reversed.next
        }
    }

    private companion object {
        const val MAX_EVENTS = 256
    }
}
