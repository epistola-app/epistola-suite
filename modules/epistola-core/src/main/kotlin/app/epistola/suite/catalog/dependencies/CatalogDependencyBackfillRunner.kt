// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.SystemUser
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component

/**
 * The 2.0 upgrade's declarations: a catalog from 1.x references other catalogs without declaring
 * them, because 1.x had no declarations, and a release of it is refused until it does (contract ADR
 * 0003). The upgrade migration queues every authored catalog that existed then
 * (`catalog_dependency_backfill`); this declares, for each, every catalog its content references —
 * selector `*`, locked to the highest release there is ([MissingDependencyDeclarer]) — and takes it
 * off the queue.
 *
 * Only queued catalogs: one created under 2.0 is never declared behind its author's back. A
 * single-owner cluster task off the startup path, retried until the queue is empty; a catalog leaves
 * the queue once it is declared, and declaring is idempotent, so a run cut short or re-dispatched is
 * harmless.
 */
@Component
class CatalogDependencyBackfillRunner(
    private val jdbi: Jdbi,
    private val declarer: MissingDependencyDeclarer,
    @Value("\${epistola.catalog.dependency-backfill.interval-ms:300000}")
    private val intervalMs: Long,
) : ClusterScheduledTaskHandler {
    private val logger = LoggerFactory.getLogger(javaClass)
    override val taskType: String = TASK_TYPE

    @Bean
    fun catalogDependencyBackfillScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.FixedDelay(intervalMs),
        executionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
    )

    override fun handle(task: ClusterScheduledTask) = run()

    /** Declares and dequeues every queued catalog; a no-op once the queue is empty. */
    fun run() {
        val queued = jdbi.withHandle<List<Pair<String, String>>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT q.tenant_key, c.catalog_key
                FROM catalog_dependency_backfill q
                JOIN catalogs c ON c.tenant_key = q.tenant_key AND c.catalog_id = q.catalog_id
                ORDER BY q.tenant_key, c.catalog_key
                """,
            )
                .map { rs, _ -> rs.getString(1) to rs.getString(2) }
                .list()
        }
        for ((tenant, catalog) in queued) {
            val tenantKey = TenantKey.of(tenant)
            val catalogKey = CatalogKey.of(catalog)
            // An install-wide task runs with no principal; the declaring reads the catalog through
            // permission-checked queries, so it runs as the tenant's system principal.
            val added = SecurityContext.runWithPrincipal(SystemUser.principalForTenant(tenantKey)) {
                declarer.declareMissing(tenantKey, catalogKey)
            }
            if (added.isNotEmpty()) logger.info("Catalog {}/{} now declares {}", tenant, catalog, added.joinToString())
            jdbi.useHandle<Exception> { handle ->
                handle.createUpdate(
                    """
                    DELETE FROM catalog_dependency_backfill
                    WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}
                    """,
                )
                    .bind("t", tenantKey)
                    .bind("c", catalogKey)
                    .execute()
            }
        }
        if (queued.isNotEmpty()) logger.info("Declared the dependencies of {} catalog(s) from before 2.0", queued.size)
    }

    companion object {
        const val TASK_KEY = "core.catalog-dependency-backfill"
        const val ROUTING_KEY = "system:core.catalog-dependency-backfill"
        const val TASK_TYPE = "core.catalog-dependency-backfill"
    }
}
