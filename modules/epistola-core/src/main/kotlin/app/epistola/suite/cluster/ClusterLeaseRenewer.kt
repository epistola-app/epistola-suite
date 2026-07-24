// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.cluster

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps the lease on in-flight cluster work fresh while a handler runs.
 *
 * The cluster pollers dispatch handlers synchronously on the Spring `@Scheduled`
 * thread, so the polling thread itself cannot renew a lease while it is blocked
 * inside a long handler. This renewer periodically extends the lease on every
 * key currently being processed, using the shared [ClusterMaintenanceExecutor]
 * thread. That stops a handler which legitimately runs longer than the lease
 * duration from being reclaimed and re-run by another node — even across a
 * membership change that would otherwise reassign routing-key ownership mid-run.
 *
 * [renewIntervalMs] should be comfortably shorter than the lease duration
 * (a third is a good default) so a renewal always lands before the lease lapses.
 */
class ClusterLeaseRenewer(
    scheduler: ScheduledExecutorService,
    renewIntervalMs: Long,
    private val renew: (Set<String>) -> Unit,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val task: ScheduledFuture<*> = run {
        val interval = renewIntervalMs.coerceAtLeast(1)
        scheduler.scheduleWithFixedDelay(::renewInFlight, interval, interval, TimeUnit.MILLISECONDS)
    }

    /**
     * Marks [key] in-flight, runs [work], then clears it. The lease for [key] is
     * renewed on the shared maintenance thread for as long as [work] runs.
     */
    fun <T> withRenewal(key: String, work: () -> T): T {
        inFlight.add(key)
        try {
            return work()
        } finally {
            inFlight.remove(key)
        }
    }

    private fun renewInFlight() {
        val keys = inFlight.toSet()
        if (keys.isEmpty()) return
        try {
            renew(keys)
        } catch (e: Exception) {
            log.warn("Lease renewal failed for {} in-flight key(s): {}", keys.size, e.message, e)
        }
    }

    /** Stops renewing. The shared executor is owned by [ClusterMaintenanceExecutor], not cancelled here. */
    override fun close() {
        task.cancel(false)
    }
}
