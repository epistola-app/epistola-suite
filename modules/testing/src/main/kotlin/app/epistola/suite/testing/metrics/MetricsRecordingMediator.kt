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
    override fun <R> send(command: Command<R>): R {
        val start = System.nanoTime()
        try {
            return delegate.send(command)
        } finally {
            TestRunMetrics.recordCommand(command::class.simpleName ?: "Unknown", System.nanoTime() - start)
        }
    }

    override fun <R> query(query: Query<R>): R {
        val start = System.nanoTime()
        try {
            return delegate.query(query)
        } finally {
            TestRunMetrics.recordQuery(query::class.simpleName ?: "Unknown", System.nanoTime() - start)
        }
    }
}
