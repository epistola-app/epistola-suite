// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing.metrics

import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.Query

/**
 * Test-only [Mediator] decorator that records the duration of every command and
 * query into [TestRunMetrics], then delegates to the real mediator.
 *
 * This is the single cross-cutting seam for per-operation test metrics: tests
 * bind this decorator into `MediatorContext` (via `withMediator`), so nested
 * `.execute()` / `.query()` calls inside handlers flow through it too. A command's
 * recorded time therefore includes its nested work — e.g. `CreateTenant` includes
 * the `InstallSystemCatalog` it triggers — and `TestRunMetrics.tenantsCreated()`
 * falls out of the `CreateTenant` count with no per-test instrumentation.
 */
class MetricsRecordingMediator(val delegate: Mediator) : Mediator {
    // Per-thread stack of "child time" accumulators (one frame per in-flight
    // dispatch). Nested dispatches run synchronously on the same thread, so each
    // frame collects the inclusive time of its direct children; self = inclusive
    // − children. Work handed to another thread starts a fresh stack there.
    private val childNanosStack = ThreadLocal.withInitial { ArrayDeque<LongArray>() }

    override fun <R> send(command: Command<R>): R = timed(command::class.simpleName ?: "Unknown", isCommand = true) { delegate.send(command) }

    override fun <R> query(query: Query<R>): R = timed(query::class.simpleName ?: "Unknown", isCommand = false) { delegate.query(query) }

    private inline fun <R> timed(name: String, isCommand: Boolean, block: () -> R): R {
        val stack = childNanosStack.get()
        val frame = longArrayOf(0L) // accumulated inclusive time of this dispatch's children
        stack.addLast(frame)
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            val inclusive = System.nanoTime() - start
            stack.removeLast()
            stack.lastOrNull()?.let { it[0] += inclusive } // contribute to the parent's child time
            val self = inclusive - frame[0]
            if (isCommand) {
                TestRunMetrics.recordCommand(name, inclusive, self)
            } else {
                TestRunMetrics.recordQuery(name, inclusive, self)
            }
        }
    }
}
