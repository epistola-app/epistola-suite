// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.upgrade

import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.ContentSource
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.commands.RecordInstalledRelease
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.ReleasePublication
import app.epistola.suite.catalog.dependencies.MissingDependencyDeclarer
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
import app.epistola.suite.catalog.releases.CatalogDraft
import app.epistola.suite.catalog.releases.CatalogDrafts
import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.SystemUser
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * The 2.0 upgrade of each catalog of a 1.x installation, queued by the upgrade migration
 * (`catalog_upgrade_queue`). A subscribed catalog gets its installed release recorded as a release it
 * can render from ([RecordInstalledRelease]): 1.x kept none. For each authored catalog:
 *
 * 1. **Declarations.** 1.x had none, so its catalogs reference others (the system catalog's fonts
 *    and attributes, a shared theme) undeclared, and 2.0 refuses to release that (contract ADR 0003).
 *    Every catalog its content references is declared `*`, locked to the highest release there is
 *    ([MissingDependencyDeclarer]).
 * 2. **A first release.** No 1.x release kept its content, so the upgrade migration removed them
 *    and nothing could render. The catalog is released once from what was last published before 2.0
 *    ([ContentSource.PUBLISHED_BEFORE_2_0]), numbered the patch after its highest 1.x release (or
 *    1.0.0), not published to Exchange and deployed nowhere: operators deploy it. Its draft, the working copy with any unpublished 1.x
 *    work, opens on top. A catalog with nothing to release, or one that already has a release, gets
 *    none.
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
    meterRegistry: MeterRegistry,
) : ClusterScheduledTaskHandler {
    private val logger = LoggerFactory.getLogger(javaClass)
    override val taskType: String = TASK_TYPE

    /** Catalogs still waiting for their 2.0 upgrade, as of the last run on this node; -1 before it. */
    private val waiting = AtomicLong(-1)

    init {
        Gauge.builder("epistola.catalog.upgrade.queued", waiting) { it.get().toDouble() }
            .description("Catalogs still waiting for their 2.0 upgrade (first release), as of this node's last run; -1 before its first")
            .register(meterRegistry)
    }

    @Bean
    fun catalogUpgradeScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        // At startup: after an upgrade from 1.x nothing generates until the first releases exist.
        schedule = ClusterScheduledTaskSchedule.FixedDelay(intervalMs, initialDelayMs = 0),
        executionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
    )

    override fun handle(task: ClusterScheduledTask) = run()

    /** [continuesAfter]: the catalog's highest 1.x release, which the migration recorded before removing it. */
    private data class Queued(val catalogId: CatalogId, val subscribed: Boolean, val continuesAfter: String?) {
        val tenantKey: TenantKey get() = catalogId.tenantKey
        val catalogKey: CatalogKey get() = catalogId.key
    }

    /** Upgrades every queued catalog it can; a no-op once the queue is empty. */
    fun run() {
        var pending = queued()
        waiting.set(pending.size.toLong())
        if (pending.isEmpty()) return
        val queuedAtStart = pending.size
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
        waiting.set(pending.size.toLong())
        if (pending.isEmpty()) {
            logger.info("2.0 upgrade finished: all {} queued catalogs upgraded. Deploy their releases to the environments that should use them.", queuedAtStart)
        } else {
            logger.warn("2.0 upgrade: {} of {} catalogs upgraded, {} waiting (see catalog_upgrade_queue.last_error)", queuedAtStart - pending.size, queuedAtStart, pending.size)
        }
    }

    private fun upgrade(catalog: Queued) = SecurityContext.runWithPrincipal(SystemUser.principalForTenant(catalog.tenantKey)) {
        if (catalog.subscribed) {
            // Its declarations and content are the publisher's: only the release is missing.
            RecordInstalledRelease(catalog.catalogId).execute()
                ?.let { logger.info("Catalog {}/{} recorded its installed release {}", catalog.tenantKey.value, catalog.catalogKey.value, it) }
            return@runWithPrincipal
        }
        val declared = declarer.declareMissing(catalog.catalogId)
        if (declared.isNotEmpty()) {
            logger.info("Catalog {}/{} now declares {}", catalog.tenantKey.value, catalog.catalogKey.value, declared.joinToString())
        }
        // A catalog it depends on may have been released since it was declared, earlier in this run.
        declarer.pinUnpinned(catalog.catalogId)
        if (!hasRelease(catalog) &&
            contentBuilder.build(catalog.catalogId, ContentSource.PUBLISHED_BEFORE_2_0).resourceEntries.isNotEmpty()
        ) {
            // What it released using a catalog the tenant lacks cannot be declared: say which, not what releasing then trips over.
            val unknown = declarer.unknownCatalogs(catalog.catalogId, ContentSource.PUBLISHED_BEFORE_2_0)
            check(unknown.isEmpty()) {
                "its content uses ${if (unknown.size == 1) "catalog" else "catalogs"} ${unknown.joinToString { "'$it'" }}, which this tenant does not have: " +
                    "install ${if (unknown.size == 1) "it" else "them"}, or change what uses ${if (unknown.size == 1) "it" else "them"}, and it is upgraded on the next run"
            }
            requireNotNull(jdbi.withHandle<CatalogDraft?, Exception> { drafts.next(it, catalog.catalogId) }) {
                "catalog ${catalog.catalogKey.value} has no next release to publish"
            }
            // What was published before 2.0 becomes the next patch after its latest release, 1.x ones
            // included, or the first version.
            val released = ListCatalogReleaseSummaries(catalog.catalogId).query().map { it.version }
            val version = CatalogDrafts.latest(released + listOfNotNull(catalog.continuesAfter))
                ?.let { SemVer.parse(it).bumpPatch().toString() } ?: CatalogDrafts.FIRST
            ReleaseCatalogDraft(
                catalog = catalog.catalogId,
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
            SELECT q.tenant_key, c.namespace, c.catalog_key, c.type = 'SUBSCRIBED' AS subscribed, q.continues_after
            FROM catalog_upgrade_queue q
            JOIN catalogs c ON c.tenant_key = q.tenant_key AND c.catalog_id = q.catalog_id
            ORDER BY q.tenant_key, c.catalog_key
            """,
        )
            .map { rs, _ -> Queued(CatalogId(rs.getString(2), CatalogKey.of(rs.getString(3)), app.epistola.suite.common.ids.TenantId(TenantKey.of(rs.getString(1)))), rs.getBoolean(4), rs.getString(5)) }
            .list()
    }

    private fun hasRelease(catalog: Queued): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createQuery("SELECT EXISTS (SELECT 1 FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")})")
            .bind("t", catalog.tenantKey)
            .bindCatalog("c", catalog.catalogId)
            .mapTo(Boolean::class.java)
            .one()
    }

    /** Takes the catalog off the queue, with what 1.x published for it, which its first release now holds. */
    private fun dequeue(catalog: Queued) = jdbi.useTransaction<Exception> { handle ->
        handle.createUpdate(
            """
            DELETE FROM catalog_upgrade_published p
            USING catalog_resources r
            WHERE r.tenant_key = p.tenant_key AND r.resource_id = p.resource_id
              AND r.tenant_key = :t AND r.catalog_id = ${catalogAt("t", "c")}
            """,
        )
            .bind("t", catalog.tenantKey)
            .bindCatalog("c", catalog.catalogId)
            .execute()
        handle.createUpdate("DELETE FROM catalog_upgrade_queue WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}")
            .bind("t", catalog.tenantKey)
            .bindCatalog("c", catalog.catalogId)
            .execute()
    }

    private fun recordError(catalog: Queued, error: String) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate("UPDATE catalog_upgrade_queue SET last_error = :e WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}")
            .bind("e", error)
            .bind("t", catalog.tenantKey)
            .bindCatalog("c", catalog.catalogId)
            .execute()
    }

    companion object {
        const val TASK_KEY = "core.catalog-upgrade"
        const val ROUTING_KEY = "system:core.catalog-upgrade"
        const val TASK_TYPE = "core.catalog-upgrade"
    }
}
