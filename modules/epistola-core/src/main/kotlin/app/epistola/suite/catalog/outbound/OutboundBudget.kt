// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.outbound

import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * What one piece of work may take from catalog sources in all: [maxBytes] across every response it
 * reads, and [timeout] from the moment it starts. A release assembled from a manifest URL reads one
 * response per resource detail and per binary, each capped on its own, and nothing else would bound
 * how many there are or how long they take together: a source naming a thousand images, or answering
 * a byte at a time, would hold the memory and the thread of the installation reading it.
 *
 * Bound with [within] around the work; [OutboundSourceReader] charges each read to it. Reads outside
 * one are bounded per response only.
 */
class OutboundBudget private constructor(private val maxBytes: Long, private val deadlineNanos: Long) {

    private val spent = AtomicLong()

    /** The bytes still allowed; a read is capped at it. */
    fun remainingBytes(): Long = (maxBytes - spent.get()).coerceAtLeast(0)

    /** When the work must be done, as [System.nanoTime] counts. */
    fun deadlineNanos(): Long = deadlineNanos

    /** Refuses to start another read once the time is up. */
    fun checkTime() {
        if (System.nanoTime() - deadlineNanos > 0) throw OutboundFetchException("The catalog's source took longer than it may in all")
    }

    /** Records [bytes] read; refuses once more than [maxBytes] have been. */
    fun charge(bytes: Int) {
        if (spent.addAndGet(bytes.toLong()) > maxBytes) throw tooMuch()
    }

    /** Refuses a read that needs more than is left. */
    fun tooMuch() = OutboundFetchException("The catalog's source holds more than the $maxBytes bytes a release may have in all")

    companion object {
        private val CURRENT: ScopedValue<OutboundBudget> = ScopedValue.newInstance()

        /** The budget of the work running on this thread, or null. */
        fun current(): OutboundBudget? = if (CURRENT.isBound) CURRENT.get() else null

        /** Runs [block] with at most [maxBytes] read from sources in all, within [timeout]. */
        fun <T> within(maxBytes: Long, timeout: Duration, block: () -> T): T = ScopedValue.where(CURRENT, OutboundBudget(maxBytes, System.nanoTime() + timeout.toNanos())).call<T, RuntimeException> { block() }
    }
}
