package com.qkt.venuegateway.support

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * A serial view of a shared [pool]: what one component runs through it runs one task at a time, in the order
 * submitted, as on a thread of its own, without the component creating one. [every] runs a task again [delayMs]
 * after each run ends (a fixed delay, as `scheduleWithFixedDelay`), and keeps running it after a run that throws
 * (logged under [name]) instead of silently stopping. [close] cancels this strand's tasks only; the pool is the
 * host's. With [drain] set, tasks already submitted still run, as `shutdown` would.
 */
class Strand(
    private val pool: ScheduledExecutorService,
    private val name: String,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(Strand::class.java)
    private val queue = ArrayDeque<Runnable>()
    private val timers = ConcurrentHashMap.newKeySet<ScheduledFuture<*>>()
    private var running = false

    @Volatile private var closed = false

    /** Runs [task] after every task submitted before it. */
    fun execute(task: Runnable) {
        synchronized(this) {
            if (closed) return
            queue.addLast(task)
            if (running) return
            running = true
        }
        pool.execute(::drainQueue)
    }

    /** Runs [task] on this strand once [delayMs] has passed. */
    fun schedule(
        delayMs: Long,
        task: Runnable,
    ) {
        if (closed) return
        // The pool may run a due task before schedule() returns its future, so the task never reads the future.
        val timer = pool.schedule({ execute(task) }, delayMs, TimeUnit.MILLISECONDS)
        timers += timer
        timers.removeIf { it.isDone }
        if (closed) timer.cancel(false)
    }

    /** Runs [task] after [initialMs], then again [delayMs] after each run ends, until [close]. */
    fun every(
        initialMs: Long,
        delayMs: Long,
        task: Runnable,
    ) {
        schedule(initialMs) {
            try {
                task.run()
            } finally {
                every(delayMs, delayMs, task)
            }
        }
    }

    override fun close() = close(drain = false)

    /** Stops: no new task runs; with [drain], the ones already submitted still do. */
    fun close(drain: Boolean) {
        closed = true
        timers.forEach { it.cancel(false) }
        timers.clear()
        if (!drain) synchronized(this) { queue.clear() }
    }

    private fun drainQueue() {
        while (true) {
            val task =
                synchronized(this) {
                    queue.removeFirstOrNull() ?: run {
                        running = false
                        null
                    }
                } ?: return
            runCatching { task.run() }.onFailure { log.error("strand {}: a task failed: {}", name, it.message, it) }
        }
    }
}
