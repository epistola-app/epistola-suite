// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.observability.recordScheduledTask
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.storage.BLOB_IS_HELD
import io.micrometer.core.instrument.MeterRegistry
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/** What a collection took: contents (with their binaries and references), and blobs. */
data class ContentCollection(
    val contents: Int,
    val blobs: Int,
    /** Credentials no content names any more (`resource_credentials`, ADR 0033). */
    val credentials: Int = 0,
)

/**
 * Collects content nothing points at (ADR 0033): `resource_contents` that no `release_resources`
 * row and no other content refers to, stored longer ago than the grace period, and then the bytes
 * in `asset_content` that nothing holds any more.
 *
 * - **Per tenant, in batches** of [StorageGcProperties.batchSize], each its own transaction, with
 *   [StorageGcProperties.pause] between them. A batch selects `FOR UPDATE SKIP LOCKED`: content a
 *   save holds (storing content again touches and locks its row, [ResourceContentStore]) is skipped,
 *   not waited for.
 * - **Safe by the foreign key** from `release_resources`: content a row points at cannot be deleted,
 *   so a wrong query fails instead of losing data. A lifted child (a template's variant model) is
 *   held by its parent's payload, not by a row, so it is kept while any content refers to it and
 *   goes in a later pass, after its parent.
 * - Deleting a content cascades to its `resource_binaries` and `resource_references`. The second
 *   pass then deletes `asset_content` that neither an asset nor any content's binaries hold
 *   ([BLOB_IS_HELD], the rule `ContentReaper` uses), older than the grace period.
 *
 * A single-owner cluster task in a configurable window, nightly by default, which an operator can
 * also start by hand for one tenant ([CollectResourceContents]); `epistola.storage.orphaned_contents`
 * gauges what is left to collect.
 */
@Component
class ResourceContentCollector(
    private val jdbi: Jdbi,
    private val properties: StorageGcProperties,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val orphans = AtomicLong(0)

    init {
        meterRegistry.gauge("epistola.storage.orphaned_contents", orphans) { it.get().toDouble() }
    }

    /** Collects every tenant's orphaned content, then the blobs nothing holds. */
    fun collectAll(): ContentCollection = meterRegistry.recordScheduledTask("resource-content-collector") {
        val contents = tenantsWithCandidates().sumOf { collectContents(it) }
        val credentials = collectCredentials(null)
        val blobs = collectBlobs()
        orphans.set(countOrphans(null))
        if (contents + blobs + credentials > 0) logger.info("Collected {} resource content(s), {} credential(s) and {} blob(s) nothing points at", contents, credentials, blobs)
        ContentCollection(contents, blobs, credentials)
    }

    /** Collects one tenant's orphaned content, then the blobs nothing holds. */
    fun collect(tenantKey: TenantKey): ContentCollection {
        val contents = collectContents(tenantKey)
        val credentials = collectCredentials(tenantKey)
        val blobs = collectBlobs()
        orphans.set(countOrphans(null))
        return ContentCollection(contents, blobs, credentials)
    }

    /**
     * Deletes the credentials of [tenantKey] (null: every tenant) that no content names any more
     * (`source.credential`), stored longer ago than the grace period, so one a save is about to name
     * is never taken from under it.
     */
    private fun collectCredentials(tenantKey: TenantKey?): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createUpdate(
            """
            DELETE FROM resource_credentials rc
            WHERE (CAST(:t AS TEXT) IS NULL OR rc.tenant_key = :t)
              AND rc.created_at < NOW() - CAST(:grace AS INTERVAL)
              AND NOT EXISTS (
                  SELECT 1 FROM resource_contents c
                  WHERE c.tenant_key = rc.tenant_key AND c.payload -> 'source' ->> 'credential' = CAST(rc.credential_id AS TEXT))
            """,
        )
            .bind("t", tenantKey?.value)
            .bind("grace", interval())
            .execute()
    }

    /** What is left to collect: orphaned content past the grace period, of [tenantKey] or of every tenant. */
    fun countOrphans(tenantKey: TenantKey?): Long = jdbi.withHandle<Long, Exception> { handle ->
        handle.createQuery(
            """
            WITH $CHILDREN
            SELECT count(*) FROM resource_contents c
            WHERE (CAST(:t AS TEXT) IS NULL OR c.tenant_key = :t)
              AND c.stored_at < NOW() - CAST(:grace AS INTERVAL)
              AND $UNREFERENCED
            """,
        )
            .bind("t", tenantKey?.value)
            .bind("grace", interval())
            .mapTo(Long::class.java)
            .one()
    }

    private fun tenantsWithCandidates(): List<TenantKey> = jdbi.withHandle<List<TenantKey>, Exception> { handle ->
        handle.createQuery("SELECT DISTINCT tenant_key FROM resource_contents WHERE stored_at < NOW() - CAST(:grace AS INTERVAL) ORDER BY tenant_key")
            .bind("grace", interval())
            .mapTo(String::class.java)
            .list()
            .map(TenantKey::of)
    }

    /**
     * Deletes [tenantKey]'s orphaned content, batch by batch, until a pass finds none. A pass that
     * removes a parent frees its children for the next one.
     */
    private fun collectContents(tenantKey: TenantKey): Int {
        var total = 0
        while (true) {
            val deleted = jdbi.inTransaction<Int, Exception> { handle -> deleteBatch(handle, tenantKey) }
            total += deleted
            if (deleted == 0) return total
            pause()
        }
    }

    private fun deleteBatch(handle: Handle, tenantKey: TenantKey): Int {
        val batch = handle.createQuery(
            """
            WITH $CHILDREN
            SELECT c.content_hash FROM resource_contents c
            WHERE c.tenant_key = :t
              AND c.stored_at < NOW() - CAST(:grace AS INTERVAL)
              AND $UNREFERENCED
            ORDER BY c.content_hash
            LIMIT :limit
            FOR UPDATE OF c SKIP LOCKED
            """,
        )
            .bind("t", tenantKey)
            .bind("grace", interval())
            .bind("limit", properties.batchSize)
            .mapTo(String::class.java)
            .list()
        if (batch.isEmpty()) return 0
        return handle.createUpdate("DELETE FROM resource_contents WHERE tenant_key = :t AND content_hash = ANY(:hashes)")
            .bind("t", tenantKey)
            .bindArray("hashes", String::class.java, batch.toTypedArray())
            .execute()
    }

    /** Deletes `asset_content` nothing holds, older than the grace period, batch by batch. */
    private fun collectBlobs(): Int {
        var total = 0
        while (true) {
            val deleted = jdbi.inTransaction<Int, Exception> { handle ->
                handle.createUpdate(
                    """
                    DELETE FROM asset_content
                    WHERE (scope, content_hash) IN (
                        SELECT ac.scope, ac.content_hash FROM asset_content ac
                        WHERE ac.created_at < NOW() - CAST(:grace AS INTERVAL)
                          AND NOT ($BLOB_IS_HELD)
                        LIMIT :limit
                        FOR UPDATE OF ac SKIP LOCKED
                    )
                    """,
                )
                    .bind("grace", interval())
                    .bind("limit", properties.batchSize)
                    .execute()
            }
            total += deleted
            if (deleted == 0) return total
            pause()
        }
    }

    private fun pause() {
        if (!properties.pause.isZero) Thread.sleep(properties.pause.toMillis())
    }

    private fun interval(): String = "${properties.grace.toMillis()} milliseconds"

    private companion object {
        /**
         * The contents another content's payload refers to (a template's lifted models,
         * [REVISION_REF_FIELD]), of the tenant bound as `:t` or of every tenant when it is null.
         */
        val CHILDREN = """
            children AS (
                SELECT p.tenant_key, jsonb_array_elements_text(jsonb_path_query_array(p.payload, '$.**.$REVISION_REF_FIELD')) AS content_hash
                FROM resource_contents p
                WHERE p.kind = 'template' AND (CAST(:t AS TEXT) IS NULL OR p.tenant_key = :t)
            )
        """.trimIndent()

        /** For a content aliased `c`: no release's resource points at it, and no other content refers to it ([CHILDREN]). */
        val UNREFERENCED = """
            NOT EXISTS (SELECT 1 FROM release_resources r WHERE r.tenant_key = c.tenant_key AND r.content_hash = c.content_hash)
            AND NOT EXISTS (SELECT 1 FROM children k WHERE k.tenant_key = c.tenant_key AND k.content_hash = c.content_hash)
        """.trimIndent()
    }
}

/** Runs the collector on its schedule (ADR 0033): one node at a time. */
@Component
@ConditionalOnProperty(name = ["epistola.storage.gc.enabled"], havingValue = "true", matchIfMissing = true)
class ResourceContentCollectorTask(
    private val collector: ResourceContentCollector,
    private val properties: StorageGcProperties,
) : ClusterScheduledTaskHandler {
    override val taskType: String = TASK_TYPE

    @Bean
    fun resourceContentCollectorScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.Cron(properties.cron),
        executionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
    )

    override fun handle(task: ClusterScheduledTask) {
        collector.collectAll()
    }

    companion object {
        const val TASK_KEY = "core.resource-content-collector"
        const val ROUTING_KEY = "system:core.resource-content-collector"
        const val TASK_TYPE = "core.resource-content-collector"
    }
}

/**
 * Starts the collector by hand for [tenantKey] (after removing many releases, say): its orphaned
 * content past the grace period, then the blobs nothing holds. The same rules as the nightly run.
 */
data class CollectResourceContents(override val tenantKey: TenantKey) :
    Command<ContentCollection>,
    RequiresPermission,
    // It commits batch by batch, with a pause between them, as the nightly run does.
    app.epistola.suite.mediator.SelfManagedTransaction {
    override val permission get() = Permission.DIAGNOSTICS_MANAGE
}

@Component
class CollectResourceContentsHandler(
    private val collector: ResourceContentCollector,
) : CommandHandler<CollectResourceContents, ContentCollection> {
    override fun handle(command: CollectResourceContents): ContentCollection = collector.collect(command.tenantKey)
}
