package app.epistola.suite.loadtest.batch

import app.epistola.suite.loadtest.model.LoadTestRun

/**
 * Event published when a load test run is created.
 *
 * Used by synchronous execution listeners in test mode to execute load tests immediately
 * instead of waiting for the [LoadTestPoller].
 */
data class LoadTestCreatedEvent(
    val run: LoadTestRun,
)
