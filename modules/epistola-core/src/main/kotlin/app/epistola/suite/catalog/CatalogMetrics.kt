// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.catalog.releases.ReleaseChannel
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * What happens to catalogs that the mediator's command timers cannot tell: how releases arrive, by
 * channel, and what upstream checks find. Releases cut, deployments and quality runs are already
 * counted there (`epistola.mediator.command.duration{command=…}`); see `docs/metrics.md`.
 *
 * Tags stay low-cardinality: a channel or an outcome, never a tenant or a catalog.
 */
@Component
class CatalogMetrics(private val meterRegistry: MeterRegistry) {

    /** A release was cut here. Counted when its transaction commits. */
    fun releaseCut() = releaseArrived(ReleaseChannel.RELEASED)

    /** A release arrived through [via]. Counted when its transaction commits. */
    fun releaseArrived(via: ReleaseChannel) = afterCommit {
        Counter.builder(RELEASES)
            .description("Releases that came to be in a tenant, by how they arrived")
            .tag("via", via.code)
            .register(meterRegistry)
            .increment()
    }

    /** An upstream check finished with [outcome]: `ok`, or the failure it recorded. */
    fun upstreamChecked(outcome: String) {
        Counter.builder(UPSTREAM_CHECKS)
            .description("Checks of a subscribed catalog's source, by outcome")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment()
    }

    /** Run [count] once the current transaction commits, or now when there is none. */
    private fun afterCommit(count: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return count()
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = count()
            },
        )
    }

    companion object {
        const val RELEASES: String = "epistola.catalog.releases"
        const val UPSTREAM_CHECKS: String = "epistola.catalog.upstream.checks"
        const val OUTCOME_OK: String = "ok"
    }
}
