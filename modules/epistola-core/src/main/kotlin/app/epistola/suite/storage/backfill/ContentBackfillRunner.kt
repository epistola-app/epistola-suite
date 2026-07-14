package app.epistola.suite.storage.backfill

import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.storage.AssetContentStore
import app.epistola.suite.storage.ContentStore
import app.epistola.suite.storage.StorageBackend
import app.epistola.suite.storage.StorageProperties
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * One-time, forward-only migration of blobs out of the legacy shared `content_store`
 * into the two lifecycle-split stores (issue #738). Runs at startup, is advisory-locked
 * (one node), batched, resumable, and idempotent — safe to re-run every boot until the
 * legacy `content_store` is dropped in a later release.
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
    private val mediator: Mediator,
    private val appMetadata: AppMetadataService,
    @Value("\${epistola.storage.backfill.asset-batch-size:200}")
    private val assetBatchSize: Int,
) : SmartInitializingSingleton {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun afterSingletonsInstantiated() {
        // Only run when there is still work to do: once a node has completed a full
        // pass it records a marker, so every later boot (fleet-wide) short-circuits
        // here — no advisory lock, no scan queries. New content never lands in the
        // legacy store after this feature ships, so "done" is durable.
        if (appMetadata.get(COMPLETED_KEY) != null) {
            logger.debug("Content backfill already completed — skipping")
            return
        }
        // Session-level advisory lock so only one node runs the backfill. It must be
        // acquired AND released on the SAME connection — a pooled connection keeps the
        // lock across a Handle close, so we hold one dedicated Handle for its lifetime
        // and explicitly unlock before returning it to the pool. The batched work runs
        // on other pooled connections; the lock is only a cross-node mutex.
        jdbi.open().use { lockHandle ->
            val locked = lockHandle.createQuery("SELECT pg_try_advisory_lock(:key)")
                .bind("key", BACKFILL_LOCK_KEY)
                .mapTo(Boolean::class.java)
                .one()
            if (!locked) {
                logger.debug("Content backfill skipped — another node holds the lock")
                return
            }
            try {
                MediatorContext.runWithMediator(mediator) {
                    if (properties.backend == StorageBackend.POSTGRES) {
                        backfillDocuments()
                    }
                    backfillAssets()
                }
                // A full pass completed without error — mark done so future boots skip
                // entirely. (Assets with irretrievable legacy bytes stay content_hash
                // NULL and fall back to the legacy store; re-scanning can't fix them.)
                appMetadata.setAs(COMPLETED_KEY, true)
            } catch (e: Exception) {
                logger.error("Content backfill failed (will retry next boot): {}", e.message, e)
            } finally {
                lockHandle.createUpdate("SELECT pg_advisory_unlock(:key)").bind("key", BACKFILL_LOCK_KEY).execute()
            }
        }
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
        while (true) {
            val batch = jdbi.withHandle<List<AssetRow>, Exception> { handle ->
                handle.createQuery(
                    """
                    SELECT id, tenant_key, catalog_key, media_type
                    FROM assets
                    WHERE content_hash IS NULL
                    LIMIT :limit
                    """,
                )
                    .bind("limit", assetBatchSize)
                    .map { rs, _ ->
                        AssetRow(
                            id = rs.getString("id"),
                            tenantKey = rs.getString("tenant_key"),
                            catalogKey = rs.getString("catalog_key"),
                            mediaType = rs.getString("media_type"),
                        )
                    }
                    .list()
            }
            if (batch.isEmpty()) break

            val migratedThisBatch = batch.count { migrateAsset(it) }
            migratedTotal += migratedThisBatch
            // No progress on a full batch → the rest are unresolvable; stop looping.
            if (migratedThisBatch == 0) {
                logger.warn("Content backfill: {} asset(s) have no legacy bytes and were left unmigrated", batch.size)
                break
            }
        }
        if (migratedTotal > 0) logger.info("Backfilled {} asset blob(s) into asset_content", migratedTotal)
    }

    /** @return true if the asset's bytes were migrated and its pointer stamped. */
    private fun migrateAsset(row: AssetRow): Boolean {
        val legacyKey = "assets/${row.tenantKey}/${row.id}"
        val stored = legacyContentStore.get(legacyKey) ?: return false
        val bytes = stored.content.readAllBytes()
        val hash = app.epistola.suite.fonts.model.sha256Hex(bytes)
        val scope = if (row.catalogKey == "system") "system" else row.tenantKey
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
        val catalogKey: String,
        val mediaType: String,
    )

    private companion object {
        // Application-defined advisory lock key ("EpBkfil1"), high range to avoid collisions.
        const val BACKFILL_LOCK_KEY: Long = 0x4570_426B_6669_6C31L

        // app_metadata marker: set after a full pass so later boots skip the whole runner.
        const val COMPLETED_KEY = "content-backfill.completed"
    }
}
