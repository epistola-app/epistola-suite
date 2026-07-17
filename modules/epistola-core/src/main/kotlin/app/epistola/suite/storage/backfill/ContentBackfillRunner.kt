package app.epistola.suite.storage.backfill

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.storage.AssetContentStore
import app.epistola.suite.storage.ContentStore
import app.epistola.suite.storage.StorageBackend
import app.epistola.suite.storage.StorageProperties
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component

/**
 * **Transitional — remove with the `content_store` drop (#742).**
 *
 * One-time, forward-only migration of blobs out of the legacy shared `content_store`
 * into the two lifecycle-split stores (issue #738). Runs as a **single-owner background
 * cluster task** (not on the startup path, so it never races readiness on a large
 * install), batched, resumable, and idempotent. It records a completion marker after a
 * full pass so later occurrences short-circuit; the [LegacyBlobFallback] keeps documents
 * and assets readable on every node *while* it runs.
 *
 * - **Documents (PostgreSQL backend only):** copy `content_store` `documents/…` blobs
 *   into the partitioned `document_content` table, bucketed by the OWNING document's
 *   `created_at` (so they land in the right monthly partition and age out with the
 *   metadata). Blobs whose `documents` row is already gone are **pre-existing orphans**
 *   from past retention drops — they are simply not carried forward (the leak this issue
 *   fixes, discarded here). On S3 / filesystem, document blobs stay in place; only the
 *   reclaim mechanism changed, so nothing is copied.
 * - **Assets (all backends → PostgreSQL):** for each `assets` row not yet migrated
 *   (`content_hash IS NULL`), read the bytes through the legacy store, hash them,
 *   put-if-absent into the content-addressable `asset_content`, and stamp the pointer.
 */
@Component
@ConditionalOnProperty(
    name = ["epistola.storage.backfill.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class ContentBackfillRunner(
    private val jdbi: Jdbi,
    private val properties: StorageProperties,
    private val legacyContentStore: ContentStore,
    private val assetContentStore: AssetContentStore,
    private val appMetadata: AppMetadataService,
    @Value("\${epistola.storage.backfill.asset-batch-size:200}")
    private val assetBatchSize: Int,
    @Value("\${epistola.storage.backfill.interval-ms:300000}")
    private val intervalMs: Long,
) : ClusterScheduledTaskHandler {
    private val logger = LoggerFactory.getLogger(javaClass)
    override val taskType: String = TASK_TYPE

    @Bean
    fun contentBackfillScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        // FixedDelay: first run shortly after startup, then this long after each
        // completion — so it retries until done and then no-ops on the marker.
        schedule = ClusterScheduledTaskSchedule.FixedDelay(intervalMs),
        executionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
    )

    override fun handle(task: ClusterScheduledTask) = run()

    /**
     * Run one backfill pass. Single-owner scheduling gives cross-node mutual exclusion,
     * and the scheduler binds a [app.epistola.suite.mediator.MediatorContext] so
     * [app.epistola.suite.time.EpistolaClock] works here. Idempotent, so a re-dispatched
     * occurrence is safe. Exceptions propagate so the scheduler records the failure and
     * retries — partial progress is preserved.
     */
    fun run() {
        // Once a full pass has completed, every later occurrence short-circuits here —
        // one cheap metadata read, no scans. New content never lands in the legacy store
        // after this feature ships, so "done" is durable.
        if (appMetadata.get(COMPLETED_KEY) != null) {
            logger.debug("Content backfill already completed — skipping")
            return
        }
        if (properties.backend == StorageBackend.POSTGRES) {
            backfillDocuments()
        }
        backfillAssets()
        // A full pass completed without error — mark done so future occurrences skip
        // entirely. (Assets with irretrievable legacy bytes stay content_hash NULL and
        // fall back to the legacy store; re-scanning can't fix them.)
        appMetadata.setAs(COMPLETED_KEY, true)
    }

    /**
     * Copy legacy `documents/…` blobs into `document_content`, one owning-month at a time
     * (creating the partition first). Idempotent via `ON CONFLICT DO NOTHING`.
     */
    private fun backfillDocuments() {
        val months = jdbi.withHandle<List<String>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT DISTINCT to_char(date_trunc('month', d.created_at), 'YYYY-MM-DD') AS month
                FROM content_store cs
                JOIN documents d ON cs.key = 'documents/' || d.tenant_key || '/' || d.id::text
                ORDER BY month
                """,
            )
                .mapTo(String::class.java)
                .list()
        }
        if (months.isEmpty()) return

        var copied = 0
        months.forEach { month ->
            jdbi.useTransaction<Exception> { handle ->
                handle.createUpdate("SELECT epistola_create_partition('document_content'::regclass, :month::date)")
                    .bind("month", month)
                    .execute()
                copied += handle.createUpdate(
                    """
                    INSERT INTO document_content (key, content, content_type, size_bytes, created_at)
                    SELECT cs.key, cs.content, cs.content_type, cs.size_bytes, d.created_at
                    FROM content_store cs
                    JOIN documents d ON cs.key = 'documents/' || d.tenant_key || '/' || d.id::text
                    WHERE date_trunc('month', d.created_at) = :month::date
                    ON CONFLICT (key, created_at) DO NOTHING
                    """,
                )
                    .bind("month", month)
                    .execute()
            }
        }
        logger.info("Backfilled {} document blob(s) into document_content across {} month(s)", copied, months.size)
    }

    /**
     * Migrate legacy asset blobs into the content-addressable store, in batches. Loops
     * until a full batch resolves nothing (remaining rows have missing legacy bytes — a
     * pre-existing data problem, left as content_hash IS NULL and logged).
     */
    private fun backfillAssets() {
        var migratedTotal = 0
        var unresolved = 0
        // Keyset pagination by id: advance past EVERY processed row (including
        // unresolvable ones with no legacy bytes), so a batch full of unresolvable rows
        // can never strand resolvable rows behind it. A plain `LIMIT` with break-on-no-
        // progress re-selected the same unresolvable rows and could stop early.
        var lastId = "00000000-0000-0000-0000-000000000000"
        while (true) {
            val batch = jdbi.withHandle<List<AssetRow>, Exception> { handle ->
                handle.createQuery(
                    """
                    SELECT id, tenant_key, media_type, sensitive
                    FROM assets
                    WHERE content_hash IS NULL AND id > :lastId::uuid
                    ORDER BY id
                    LIMIT :limit
                    """,
                )
                    .bind("lastId", lastId)
                    .bind("limit", assetBatchSize)
                    .map { rs, _ ->
                        AssetRow(
                            id = rs.getString("id"),
                            tenantKey = rs.getString("tenant_key"),
                            mediaType = rs.getString("media_type"),
                            sensitive = rs.getBoolean("sensitive"),
                        )
                    }
                    .list()
            }
            if (batch.isEmpty()) break

            batch.forEach { if (migrateAsset(it)) migratedTotal++ else unresolved++ }
            lastId = batch.last().id
        }
        if (migratedTotal > 0) logger.info("Backfilled {} asset blob(s) into asset_content", migratedTotal)
        if (unresolved > 0) logger.warn("Content backfill: {} asset(s) have no legacy bytes and were left unmigrated", unresolved)
    }

    /** @return true if the asset's bytes were migrated and its pointer stamped. */
    private fun migrateAsset(row: AssetRow): Boolean {
        val legacyKey = "assets/${row.tenantKey}/${row.id}"
        val stored = legacyContentStore.get(legacyKey) ?: return false
        val bytes = stored.content.readAllBytes()
        val hash = app.epistola.suite.fonts.model.sha256Hex(bytes)
        val scope = app.epistola.suite.assets.assetContentScope(row.sensitive, app.epistola.suite.common.ids.TenantKey.of(row.tenantKey))
        assetContentStore.putIfAbsent(scope, hash, bytes, row.mediaType, bytes.size.toLong())
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                "UPDATE assets SET content_hash = :hash WHERE id = :id::uuid AND tenant_key = :tenantKey AND content_hash IS NULL",
            )
                .bind("hash", hash)
                .bind("id", row.id)
                .bind("tenantKey", row.tenantKey)
                .execute()
        }
        return true
    }

    private data class AssetRow(
        val id: String,
        val tenantKey: String,
        val mediaType: String,
        val sensitive: Boolean,
    )

    companion object {
        const val TASK_KEY = "core.content-backfill"
        const val ROUTING_KEY = "system:core.content-backfill"
        const val TASK_TYPE = "core.content-backfill"

        // app_metadata marker: set after a full pass so later occurrences skip the runner.
        const val COMPLETED_KEY = "content-backfill.completed"
    }
}
