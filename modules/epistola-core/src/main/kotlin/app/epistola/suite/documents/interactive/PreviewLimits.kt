// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.interactive

import app.epistola.suite.common.ids.GenerationRequestKey
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * Who may render a form preview right now, on this node: each form one at a time and [perMinute] a
 * minute, and all forms together at most [nodeConcurrency] at once. A preview renders on the request
 * thread, outside the job queue and its admission control, so the node-wide bound is what keeps
 * previews from crowding out the queue's renders. The per-form counters live in bounded caches, so
 * forms nobody previews any more cost nothing.
 */
class PreviewLimits(
    private val perMinute: Int,
    nodeConcurrency: Int,
    ticker: Ticker = Ticker.systemTicker(),
) {
    private val node = Semaphore(nodeConcurrency)
    private val inFlight = Caffeine.newBuilder().maximumSize(MAX_TRACKED_FORMS).expireAfterAccess(Duration.ofMinutes(10)).ticker(ticker)
        .build<GenerationRequestKey, Semaphore>()
    private val counts = Caffeine.newBuilder().maximumSize(MAX_TRACKED_FORMS).expireAfterWrite(Duration.ofMinutes(1)).ticker(ticker)
        .build<GenerationRequestKey, AtomicInteger>()

    /** Runs [render] when [form] is within every limit, and returns its result; null when it is not. */
    fun <T : Any> withinLimits(form: GenerationRequestKey, render: () -> T): T? {
        if (counts.get(form) { AtomicInteger() }.incrementAndGet() > perMinute) return null
        val gate = inFlight.get(form) { Semaphore(1) }
        if (!gate.tryAcquire()) return null
        try {
            if (!node.tryAcquire()) return null
            try {
                return render()
            } finally {
                node.release()
            }
        } finally {
            gate.release()
        }
    }

    private companion object {
        const val MAX_TRACKED_FORMS = 10_000L
    }
}
