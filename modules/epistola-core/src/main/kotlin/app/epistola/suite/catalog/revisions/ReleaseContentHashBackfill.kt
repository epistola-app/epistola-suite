// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component

/**
 * Gives every release that has none its content hash (ADR 0034, `catalog_releases.content_hash`).
 *
 * A release cut or received since releases are content-addressed records its content hash with it;
 * one from before has the fingerprint it was cut with (V5 or older), and its content hash is computed
 * from what it retained. That needs the contract library's canonical form, which a SQL migration
 * cannot reproduce, and every retained revision and binary read back, which a Kotlin migration would
 * do inside the upgrade's one transaction on every node's startup path. So it runs here instead: a
 * single-owner cluster task off the startup path, due at startup, each release in a transaction of
 * its own. Idempotent: it only fills a hash that is still null, and a release whose content cannot
 * be read back is logged and left for the next run. Until a release has its hash, whatever asks for
 * it computes and records it on the spot ([ReleaseIdentity.contentHash]), so no decision waits for
 * this task.
 *
 * A release whose retained content no longer reproduces the fingerprint it was recorded with gets
 * none, here or on the spot, and is logged each run ([ReleaseIdentity.contentHash] says why).
 */
@Component
class ReleaseContentHashBackfill(
    private val jdbi: Jdbi,
    private val identity: ReleaseIdentity,
) : ClusterScheduledTaskHandler {
    private val logger = LoggerFactory.getLogger(javaClass)
    override val taskType: String = TASK_TYPE

    @Bean
    fun releaseContentHashScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        // At startup: the releases of an installation upgraded from before content hashes get theirs at once.
        schedule = ClusterScheduledTaskSchedule.FixedDelay(INTERVAL_MS, initialDelayMs = 0),
        executionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
    )

    override fun handle(task: ClusterScheduledTask) {
        run()
    }

    /** Hashes every release without a content hash. @return how many got one. */
    fun run(): Int {
        val pending = pending()
        if (pending.isEmpty()) return 0
        var hashed = 0
        for ((tenantKey, catalogKey, version) in pending) {
            val hash = runCatching { jdbi.inTransaction<String?, Exception> { identity.contentHash(it, tenantKey, catalogKey, version) } }
                .onFailure { logger.warn("Content hash of release {}@{} of tenant {} not computed, will retry: {}", catalogKey.value, version, tenantKey.value, it.message) }
                .getOrNull()
            if (hash != null) hashed++
        }
        if (hashed < pending.size) {
            logger.warn(
                "Content hashes: {} of {} release(s) without one got theirs; the other {} could not be read back or no longer hold what their fingerprint names (see above)",
                hashed,
                pending.size,
                pending.size - hashed,
            )
        } else {
            logger.info("Content hashes: {} of {} release(s) without one got theirs", hashed, pending.size)
        }
        return hashed
    }

    private fun pending(): List<Triple<TenantKey, CatalogKey, String>> = jdbi.withHandle<List<Triple<TenantKey, CatalogKey, String>>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT r.tenant_key, c.catalog_key, r.version FROM catalog_releases r
            JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id
            WHERE r.content_hash IS NULL
            ORDER BY r.tenant_key, c.catalog_key, r.version
            """,
        )
            .map { rs, _ -> Triple(TenantKey.of(rs.getString(1)), CatalogKey.of(rs.getString(2)), rs.getString(3)) }
            .list()
    }

    companion object {
        const val TASK_KEY = "core.release-content-hash"
        const val ROUTING_KEY = "system:core.release-content-hash"
        const val TASK_TYPE = "core.release-content-hash"

        /** After the first run there is rarely anything left: every new release records its hash. */
        const val INTERVAL_MS: Long = 6 * 60 * 60 * 1000L
    }
}
