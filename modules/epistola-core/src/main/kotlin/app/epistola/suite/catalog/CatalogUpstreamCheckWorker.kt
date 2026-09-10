// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component

/**
 * Asks each subscribed catalog's source, on a schedule, whether it has published anything newer.
 *
 * This is what makes the answer available to someone who is not looking at the catalogs page —
 * before this, checking for updates happened only while that page rendered, so a release could sit
 * unnoticed for as long as nobody visited.
 *
 * Source-agnostic: it resolves a [CatalogUpstreamProbe] per catalog and knows nothing about what
 * any of them talk to. That is also why it lives here rather than in the Exchange integration; a
 * catalog subscribed from a URL needs exactly the same thing.
 *
 * Runs as a `single_owner` cluster task, but does not depend on being one: the store claims with
 * `SKIP LOCKED` under an expiring lease, so an overlap merely wastes a request and a node that dies
 * holding claims does not strand its catalogs.
 */
@Component
class CatalogUpstreamCheckWorker(
    private val properties: CatalogUpstreamCheckProperties,
    private val store: CatalogUpstreamCheckStore,
    private val probes: List<CatalogUpstreamProbe>,
) : ClusterScheduledTaskHandler {
    private val log = LoggerFactory.getLogger(javaClass)
    override val taskType: String = TASK_TYPE

    @Bean
    fun catalogUpstreamCheckScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.FixedDelay(properties.pollIntervalMs),
        enabled = properties.enabled,
    )

    override fun handle(task: ClusterScheduledTask) = run()

    fun run() {
        if (!properties.enabled) return
        store.claimDue(properties.batchSize, properties.claimLease).forEach(::check)
    }

    /**
     * Checks one catalog, recording whatever came back.
     *
     * Every failure is recorded and stepped past. A source being unreachable is not this
     * installation's problem to escalate, and one catalog's broken source must never stop the rest
     * from being checked — which is the whole reason the loop catches rather than propagates.
     */
    private fun check(catalog: Catalog) {
        val probe = probes.firstOrNull { it.supports(catalog) }
        if (probe == null) {
            // A source nothing can read is not a failure that will resolve itself, but it is also
            // not worth retrying quickly. Recorded so it shows on the catalog rather than vanishing.
            store.recordFailure(
                catalog.tenantKey,
                catalog.id,
                CatalogUpstreamCheckFailure.PROTOCOL_ERROR,
                "No probe can read '${catalog.sourceUrl}'",
                properties.interval,
            )
            return
        }
        try {
            store.recordSuccess(catalog.tenantKey, catalog.id, probe.probe(catalog), properties.interval)
        } catch (e: CatalogUpstreamCheckException) {
            store.recordFailure(catalog.tenantKey, catalog.id, e.failure, e.detail, properties.interval)
        } catch (e: Exception) {
            // A probe that throws something it did not translate is a bug in that probe, not a
            // reason to stop checking every other catalog.
            log.warn("Upstream check for {}/{} failed unexpectedly", catalog.tenantKey.value, catalog.id.value, e)
            store.recordFailure(
                catalog.tenantKey,
                catalog.id,
                CatalogUpstreamCheckFailure.PROTOCOL_ERROR,
                e.message,
                properties.interval,
            )
        }
    }

    private companion object {
        const val TASK_KEY = "core.catalog-upstream-check"
        const val ROUTING_KEY = "system:core.catalog-upstream-check"
        const val TASK_TYPE = "core.catalog-upstream-check"
    }
}
