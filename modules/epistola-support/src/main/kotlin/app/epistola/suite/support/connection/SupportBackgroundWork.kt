// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.security.SecurityContext
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** What became of work a page asked for and waited on briefly ([SupportBackgroundWork.launchAndWait]). */
sealed interface SupportWorkAttempt<out T> {
    /** It finished in time, with [value]. */
    data class Done<T>(val value: T) : SupportWorkAttempt<T>

    /** The same work already ran on this node; nothing new started. */
    data object Busy : SupportWorkAttempt<Nothing>

    /** It started, and goes on in the background after the wait. */
    data object StillRunning : SupportWorkAttempt<Nothing>
}

/**
 * Runs talking to the support service off the threads the suite needs for itself: the cluster
 * scheduled-task poll (which also picks up document jobs and keeps `/actuator/health` UP) and
 * request threads. A scheduled task or a page hands its work here and returns at once; the work
 * runs on a virtual thread of this node with the caller's mediator, clock and principal.
 *
 * At most one piece of work per key runs on a node at a time: launching a key that is still
 * running does not start a second one, so a slow support service never piles up work. With
 * `again`, the running one goes round once more when it finishes, so what was asked meanwhile is
 * not lost. On shutdown nothing new starts and running work is interrupted, which ends its call to
 * the support service at once ([SupportHttp]).
 */
@Component
class SupportBackgroundWork(private val mediator: Mediator) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor: ExecutorService = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("support-work-", 0).factory())

    /** The work running per key; bounded by the number of keys the support modules use. */
    private val running = ConcurrentHashMap<String, Future<*>>()
    private val rerun: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile private var closed = false

    /**
     * Loops started and not yet ended. A loop leaves [running] before it ends (and before a launch from
     * its last moments starts the next), so only this says whether work is still in flight.
     */
    private val inFlight = java.util.concurrent.atomic.AtomicInteger()

    /** Runs on the worker after a key's loop decided to stop, before its Future completes; a test seam. */
    internal var afterLastRound: (String) -> Unit = {}

    /**
     * Starts [work] under [key] unless it already runs on this node, and returns at once. With
     * [again], a launch while it runs makes it run once more afterwards.
     *
     * @return true when this call started it.
     */
    fun launch(key: String, again: Boolean = false, work: () -> Unit): Boolean {
        if (closed) return false
        // Keys of finished work go, so per-item keys never pile up.
        running.entries.removeIf { it.value.isDone }
        var started = false
        running.compute(key) { _, current ->
            if (current != null && !current.isDone) {
                if (again) rerun += key
                current
            } else {
                started = true
                inFlight.incrementAndGet()
                try {
                    executor.submit(MediatorContext.runnable(mediator, SecurityContext.currentOrNull()) { runLoop(key, work) })
                } catch (e: java.util.concurrent.RejectedExecutionException) {
                    inFlight.decrementAndGet()
                    throw e
                }
            }
        }
        return started
    }

    /**
     * For a page: starts [work] under [key] and waits for it at most [wait]. Done in time, its value
     * (or its exception, rethrown here); otherwise it goes on in the background. Never waits behind
     * work that already runs under [key].
     */
    fun <T> launchAndWait(key: String, wait: Duration, work: () -> T): SupportWorkAttempt<T> {
        val result = CompletableFuture<T>()
        val started = launch(key) {
            try {
                result.complete(work())
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        if (!started) return SupportWorkAttempt.Busy
        return try {
            SupportWorkAttempt.Done(result.get(wait.toMillis(), TimeUnit.MILLISECONDS))
        } catch (_: TimeoutException) {
            SupportWorkAttempt.StillRunning
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** Whether work under [key] runs on this node now. */
    fun isRunning(key: String): Boolean = running[key]?.isDone == false

    /** Waits until no work runs on this node, at most [timeout]; for tests. */
    fun awaitIdle(timeout: Duration = Duration.ofSeconds(30)): Boolean {
        val until = System.nanoTime() + timeout.toNanos()
        while (inFlight.get() > 0 || running.values.any { !it.isDone }) {
            if (System.nanoTime() > until) return false
            Thread.sleep(10)
        }
        return true
    }

    private fun runLoop(key: String, work: () -> Unit) {
        try {
            while (true) {
                rerun -= key
                try {
                    work()
                } catch (e: Exception) {
                    if (closed) return
                    log.warn("Support work failed work={} reason=\"{}\"", key, e.message, e)
                }
                if (closed || !goesOn(key)) break
            }
            afterLastRound(key)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    /**
     * Whether [key] runs once more; if not, it leaves [running] in the same atomic step. [launch]
     * decides under the same lock, so a launch either asks this loop for a re-run or, once the key
     * is gone, starts a new one: a re-run asked for as the loop stops is never lost.
     */
    private fun goesOn(key: String): Boolean {
        var again = false
        running.compute(key) { _, current ->
            if (key in rerun) {
                again = true
                current
            } else {
                null
            }
        }
        return again
    }

    @EventListener(ContextClosedEvent::class)
    fun contextClosing() {
        closed = true
    }

    @PreDestroy
    fun close() {
        closed = true
        executor.shutdownNow()
        if (!executor.awaitTermination(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)) {
            log.info("Support work still running at shutdown was left to end with the process work={}", running.filterValues { !it.isDone }.keys)
        }
    }

    private companion object {
        const val SHUTDOWN_WAIT_MS = 2_000L
    }
}
