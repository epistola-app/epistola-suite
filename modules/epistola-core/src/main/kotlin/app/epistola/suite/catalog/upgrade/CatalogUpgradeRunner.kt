// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.upgrade

import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.ContentSource
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.commands.RecordInstalledRelease
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.ReleasePublication
import app.epistola.suite.catalog.dependencies.MissingDependencyDeclarer
import app.epistola.suite.catalog.versions.CatalogDrafts
import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.SystemUser
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component

/**
 * The 2.0 upgrade of each catalog of a 1.x installation, queued by the upgrade migration
 * (`catalog_upgrade_queue`). A subscribed catalog gets its installed release recorded as a release it
 * can render from ([RecordInstalledRelease]): 1.x kept none. For each authored catalog:
 *
 * 1. **Declarations.** 1.x had none, so its catalogs reference others (the system catalog's fonts
 *    and attributes, a shared theme) undeclared, and 2.0 refuses to release that (contract ADR 0003).
 *    Every catalog its content references is declared `*`, locked to the highest release there is
 *    ([MissingDependencyDeclarer]).
 * 2. **A first release.** No 1.x release kept its content, so after the upgrade nothing could
 *    render. The catalog is released once from what was last published before 2.0
 *    ([ContentSource.PUBLISHED_BEFORE_2_0]), numbered as its draft, not published to Exchange and
 *    deployed nowhere: operators deploy it. Its draft, the working copy with any unpublished 1.x
 *    work, opens on top. A catalog with nothing to release, or one that already has a release with
 *    content, gets none.
 *
 * Then the catalog leaves the queue. A catalog released before the ones it depends on cannot pin
 * them, so catalogs are taken in rounds until a round finishes none; what is left stays queued with
 * its reason in `last_error` and is tried again on the next run. A catalog created under 2.0 is never
 * queued, so nothing is declared or released behind its author's back.
 *
 * A single-owner cluster task off the startup path. Each step is idempotent, so a run cut short or
 * re-dispatched is harmless. It runs as each tenant's system principal: the scheduler binds none
 * for an install-wide task, and these steps go through permission-checked messages.
 */
@Component
class CatalogUpgradeRunner(
    private val jdbi: Jdbi,
    private val declarer: MissingDependencyDeclarer,
    private val contentBuilder: CatalogContentBuilder,
    private val drafts: CatalogDrafts,
    @Value("\${epistola.catalog.upgrade.interval-ms:300000}")
    private val intervalMs: Long,
) : ClusterScheduledTaskHandler {
    private val logger = LoggerFactory.getLogger(javaClass)
    override val taskType: String = TASK_TYPE

    @Bean
    fun catalogUpgradeScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.FixedDelay(intervalMs),
        executionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
    )

    override fun handle(task: ClusterScheduledTask) = run()

    private data class Queued(val tenantKey: TenantKey, val catalogKey: CatalogKey, val subscribed: Boolean)

    /** Upgrades every queued catalog it can; a no-op once the queue is empty. */
    fun run() {
        var pending = queued()
        if (pending.isEmpty()) return
        val errors = mutableMapOf<Queued, String>()
        while (pending.isNotEmpty()) {
            val done = pending.filter { catalog ->
                runCatching { upgrade(catalog) }
                    .onFailure { errors[catalog] = it.message ?: it::class.simpleName.orEmpty() }
                    .isSuccess
            }
            done.forEach {
                dequeue(it)
                errors.remove(it)
            }
            if (done.isEmpty()) break
            pending = pending - done.toSet()
        }
        pending.forEach { catalog ->
            val error = errors[catalog].orEmpty()
            logger.warn("2.0 upgrade of catalog {}/{} not finished, will retry: {}", catalog.tenantKey.value, catalog.catalogKey.value, error)
            recordError(catalog, error)
        }
    }

    private fun upgrade(catalog: Queued) = SecurityContext.runWithPrincipal(SystemUser.principalForTenant(catalog.tenantKey)) {
        if (catalog.subscribed) {
            // Its declarations and content are the publisher's: only the release is missing.
            RecordInstalledRelease(catalog.tenantKey, catalog.catalogKey).execute()
                ?.let { logger.info("Catalog {}/{} recorded its installed release {}", catalog.tenantKey.value, catalog.catalogKey.value, it) }
            return@runWithPrincipal
        }
        val declared = declarer.declareMissing(catalog.tenantKey, catalog.catalogKey)
        if (declared.isNotEmpty()) {
            logger.info("Catalog {}/{} now declares {}", catalog.tenantKey.value, catalog.catalogKey.value, declared.joinToString())
        }
        if (!hasRetainedRelease(catalog) &&
            contentBuilder.build(catalog.tenantKey, catalog.catalogKey, ContentSource.PUBLISHED_BEFORE_2_0).resourceEntries.isNotEmpty()
        ) {
            val version = requireNotNull(jdbi.withHandle<String?, Exception> { drafts.draft(it, catalog.tenantKey, catalog.catalogKey)?.version }) {
                "catalog ${catalog.catalogKey.value} has no draft to release"
            }
            ReleaseCatalogVersion(
                tenantKey = catalog.tenantKey,
                catalogKey = catalog.catalogKey,
                version = version,
                notes = "What was published before the upgrade to 2.0.",
                publication = ReleasePublication.SUPPRESS,
                source = ContentSource.PUBLISHED_BEFORE_2_0,
            ).execute()
            logger.info("Catalog {}/{} released {} from its content published before 2.0", catalog.tenantKey.value, catalog.catalogKey.value, version)
        }
    }

    private fun queued(): List<Queued> = jdbi.withHandle<List<Queued>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT q.tenant_key, c.catalog_key, c.type = 'SUBSCRIBED' AS subscribed
            FROM catalog_upgrade_queue q
            JOIN catalogs c ON c.tenant_key = q.tenant_key AND c.catalog_id = q.catalog_id
            ORDER BY q.tenant_key, c.catalog_key
            """,
        )
            .map { rs, _ -> Queued(TenantKey.of(rs.getString(1)), CatalogKey.of(rs.getString(2)), rs.getBoolean(3)) }
            .list()
    }

    private fun hasRetainedRelease(catalog: Queued): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createQuery("SELECT EXISTS (SELECT 1 FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND content_retained)")
            .bind("t", catalog.tenantKey)
            .bind("c", catalog.catalogKey)
            .mapTo(Boolean::class.java)
            .one()
    }

    private fun dequeue(catalog: Queued) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate("DELETE FROM catalog_upgrade_queue WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}")
            .bind("t", catalog.tenantKey)
            .bind("c", catalog.catalogKey)
            .execute()
    }

    private fun recordError(catalog: Queued, error: String) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate("UPDATE catalog_upgrade_queue SET last_error = :e WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}")
            .bind("e", error)
            .bind("t", catalog.tenantKey)
            .bind("c", catalog.catalogKey)
            .execute()
    }

    companion object {
        const val TASK_KEY = "core.catalog-upgrade"
        const val ROUTING_KEY = "system:core.catalog-upgrade"
        const val TASK_TYPE = "core.catalog-upgrade"
    }
}
