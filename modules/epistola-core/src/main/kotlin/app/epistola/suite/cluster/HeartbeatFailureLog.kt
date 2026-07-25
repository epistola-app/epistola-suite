// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.cluster

import org.slf4j.Logger

/**
 * Deduplicates heartbeat outcome logging so a sustained outage does not flood
 * the log with a full-stack WARN on every interval.
 *
 * The heartbeat runs on a fixed-delay schedule and must survive a transient
 * database blip (a `scheduleWithFixedDelay` task that throws is cancelled
 * forever), so it swallows and keeps beating. Without throttling, a database
 * that is unreachable for a stretch produces one stack-trace WARN per interval.
 * Instead:
 * - the **first** failure in a run logs WARN with the throwable (fully
 *   diagnosable),
 * - subsequent consecutive failures log DEBUG (message only),
 * - the first success after a failure run logs an INFO recovery note,
 * - failures observed while shutting down log DEBUG (the connection going away
 *   as the context tears down is expected, not an incident).
 */
internal class HeartbeatFailureLog(private val log: Logger) {
    @Volatile
    var consecutiveFailures = 0
        private set

    fun recordSuccess() {
        if (consecutiveFailures > 0) {
            log.info("Cluster node heartbeat recovered after {} failed attempt(s)", consecutiveFailures)
            consecutiveFailures = 0
        }
    }

    fun recordFailure(error: Throwable, shuttingDown: Boolean) {
        consecutiveFailures++
        when {
            shuttingDown -> log.debug("Cluster node heartbeat interrupted during shutdown")
            consecutiveFailures == 1 -> log.warn("Cluster node heartbeat failed: {}", error.message, error)
            else -> log.debug("Cluster node heartbeat still failing (attempt {}): {}", consecutiveFailures, error.message)
        }
    }
}
