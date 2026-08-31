// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.statement.StatementContext
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * One publication in the outbox, without its archive.
 *
 * The retained ZIP can be as large as the catalog size limit, so it is never carried on this row:
 * it is loaded with [CatalogPublicationStore.loadArchive] only on the branch that actually submits
 * bytes. Claiming, polling, and retry accounting all work from metadata alone.
 */
data class CatalogReleasePublication(
    val id: UUID,
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
    val namespace: String,
    val status: CatalogPublicationStatus,
    val idempotencyKey: UUID,
    val remotePublicationId: UUID?,
    val attempts: Int,
    val archiveRetained: Boolean,
    val lastError: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

/** The longest-outstanding unfinished publication, with its age measured by the database clock. */
data class OldestActivePublication(val since: OffsetDateTime, val age: Duration)

/**
 * Sole owner of the `catalog_release_publications` SQL — the durable publication outbox.
 *
 * The worker above it holds the state machine and the remote conversation; everything that reads
 * or writes an outbox row goes through here, so lease handling, backoff arithmetic, and the
 * archive-retention rule each exist once. Mirrors how `TenantBackupStore` fronts backup storage.
 */
@Component
class CatalogPublicationStore(private val jdbi: Jdbi) {

    /** Lease held by a node while it processes a claimed row; a crashed node's rows free up after this. */
    private val claimLease: Duration = Duration.ofMinutes(5)

    fun insert(
        handle: Handle,
        id: UUID,
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        version: String,
        fingerprint: String,
        namespace: String,
        archive: ByteArray,
        idempotencyKey: UUID,
    ) {
        handle.createUpdate(
            """
            INSERT INTO catalog_release_publications
                (id, tenant_key, catalog_key, version, fingerprint, namespace, archive, status, idempotency_key)
            VALUES (:id, :tenantKey, :catalogKey, :version, :fingerprint, :namespace, :archive, :status, :idempotencyKey)
            """,
        ).bind("id", id).bind("tenantKey", tenantKey).bind("catalogKey", catalogKey).bind("version", version)
            .bind("fingerprint", fingerprint).bind("namespace", namespace).bind("archive", archive)
            .bind("status", CatalogPublicationStatus.READY).bind("idempotencyKey", idempotencyKey).execute()
    }

    /**
     * Restarts a terminal publication with a fresh idempotency key, so Exchange does not deduplicate
     * the new attempt against the old one. [archive] replaces the stored bytes when the caller had
     * to rebuild them — a cancelled publication released its archive, a failed one kept it.
     */
    fun requeue(handle: Handle, id: UUID, namespace: String, idempotencyKey: UUID, archive: ByteArray? = null) {
        handle.createUpdate(
            """
            UPDATE catalog_release_publications
            SET namespace = :namespace, status = :status, idempotency_key = :idempotencyKey,
                archive = COALESCE(:archive, archive),
                remote_publication_id = NULL, attempts = 0, next_attempt_at = NOW(),
                claimed_at = NULL, last_error = NULL, updated_at = NOW()
            WHERE id = :id
            """,
        ).bind("namespace", namespace).bind("status", CatalogPublicationStatus.READY).bind("archive", archive)
            .bind("idempotencyKey", idempotencyKey).bind("id", id).execute()
    }

    /** Withdraws a publication the administrator no longer wants, releasing its retained archive. */
    fun cancel(handle: Handle, id: UUID, reason: String) {
        handle.createUpdate(
            """
            UPDATE catalog_release_publications
            SET status = :status, archive = NULL, claimed_at = NULL, last_error = :reason, updated_at = NOW()
            WHERE id = :id
            """,
        ).bind("status", CatalogPublicationStatus.CANCELLED).bind("reason", reason).bind("id", id).execute()
    }

    fun find(handle: Handle, id: UUID): CatalogReleasePublication? = handle.createQuery("$SELECT_METADATA WHERE id = :id FOR UPDATE")
        .bind("id", id).map(::map).findOne().orElse(null)

    fun findByVersion(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, version: String): CatalogReleasePublication? = handle.createQuery(
        "${SELECT_METADATA} WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND version = :version FOR UPDATE",
    ).bind("tenantKey", tenantKey).bind("catalogKey", catalogKey).bind("version", version)
        .map(::map).findOne().orElse(null)

    fun list(tenantKey: TenantKey, catalogKey: CatalogKey): List<CatalogReleasePublication> = jdbi.withHandle<List<CatalogReleasePublication>, Exception> { handle ->
        handle.createQuery(
            "$SELECT_METADATA WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey ORDER BY created_at DESC",
        ).bind("tenantKey", tenantKey).bind("catalogKey", catalogKey).map(::map).list()
    }

    /**
     * Takes up to [limit] due rows for this node. `FOR UPDATE SKIP LOCKED` keeps two pollers from
     * taking the same row and the [claimLease] returns rows abandoned by a lost node.
     */
    fun claimDue(limit: Int): List<CatalogReleasePublication> = jdbi.inTransaction<List<CatalogReleasePublication>, Exception> { handle ->
        val rows = handle.createQuery(
            """
            $SELECT_METADATA
            WHERE status = ANY(:active)
              AND next_attempt_at <= NOW()
              AND (claimed_at IS NULL OR claimed_at < NOW() - :lease * INTERVAL '1 second')
            ORDER BY created_at
            FOR UPDATE SKIP LOCKED
            LIMIT :limit
            """,
        ).bind("active", activeNames())
            .bind("lease", claimLease.toSeconds()).bind("limit", limit).map(::map).list()
        if (rows.isNotEmpty()) {
            handle.createUpdate("UPDATE catalog_release_publications SET claimed_at = NOW() WHERE id = ANY(:ids)")
                .bind("ids", rows.map(CatalogReleasePublication::id).toTypedArray()).execute()
        }
        rows
    }

    /** Loads the retained ZIP for one publication. Null once a terminal decision cleared it. */
    fun loadArchive(id: UUID): ByteArray? = jdbi.withHandle<ByteArray?, Exception> { handle ->
        handle.createQuery("SELECT archive FROM catalog_release_publications WHERE id = :id")
            .bind("id", id).map { rs, _ -> rs.getBytes("archive") }.findOne().orElse(null)
    }

    /**
     * Records Exchange's answer. A decision that [CatalogPublicationStatus.clearsArchive] releases
     * the retained bytes; anything still in flight is re-polled after [pollDelay].
     */
    fun applyRemoteState(
        id: UUID,
        status: CatalogPublicationStatus,
        remotePublicationId: UUID,
        error: String?,
        pollDelay: Duration,
    ) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate(
            """
            UPDATE catalog_release_publications
            SET status = :status, remote_publication_id = :remoteId,
                archive = CASE WHEN :clearArchive THEN NULL ELSE archive END,
                last_error = :error, claimed_at = NULL,
                next_attempt_at = NOW() + :delay * INTERVAL '1 second', updated_at = NOW()
            WHERE id = :id
            """,
        ).bind("status", status).bind("remoteId", remotePublicationId)
            .bind("clearArchive", status.clearsArchive).bind("error", error)
            .bind("delay", pollDelay.toSeconds()).bind("id", id).execute()
    }

    /**
     * Accounts for a transient failure. Retries back off exponentially, and once [maxAttempts] is
     * reached the row becomes `FAILED` — terminal until an administrator retries it — instead of
     * retrying forever while holding a retained archive.
     */
    fun recordFailure(id: UUID, error: String, attempts: Int, maxAttempts: Int, delay: Duration): CatalogPublicationStatus {
        val status = if (attempts + 1 >= maxAttempts) CatalogPublicationStatus.FAILED else CatalogPublicationStatus.RETRY
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE catalog_release_publications
                SET status = :status, attempts = attempts + 1, last_error = :error, claimed_at = NULL,
                    next_attempt_at = NOW() + :delay * INTERVAL '1 second', updated_at = NOW()
                WHERE id = :id
                """,
            ).bind("status", status).bind("error", error).bind("delay", delay.toSeconds()).bind("id", id).execute()
        }
        return status
    }

    /**
     * Releases a claim without counting a failure and defers the next look. Used when a row is
     * simply not actionable yet — enrollment is incomplete, or the tenant paused the feature — so
     * waiting rows do not spin at the poll interval.
     */
    fun defer(id: UUID, delay: Duration, reason: String? = null) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate(
            """
            UPDATE catalog_release_publications
            SET claimed_at = NULL, next_attempt_at = NOW() + :delay * INTERVAL '1 second',
                last_error = COALESCE(:reason, last_error)
            WHERE id = :id
            """,
        ).bind("delay", delay.toSeconds()).bind("reason", reason).bind("id", id).execute()
    }

    /** Re-points every publication that has not reached Exchange at a newly bound namespace. */
    fun repointUnsubmitted(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, namespace: String) {
        handle.createUpdate(
            """
            UPDATE catalog_release_publications
            SET namespace = :namespace, status = 'READY', last_error = NULL, updated_at = NOW()
            WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
              AND remote_publication_id IS NULL AND status = ANY(:active)
            """,
        ).bind("namespace", namespace).bind("tenantKey", tenantKey).bind("catalogKey", catalogKey)
            .bind("active", activeNames()).execute()
    }

    /** How many publications this tenant holds in each state. Aggregated in the database. */
    fun countsByStatus(tenantKey: TenantKey): Map<CatalogPublicationStatus, Int> = jdbi.withHandle<Map<CatalogPublicationStatus, Int>, Exception> { handle ->
        handle.createQuery(
            "SELECT status, count(*) AS total FROM catalog_release_publications WHERE tenant_key = :tenantKey GROUP BY status",
        ).bind("tenantKey", tenantKey)
            .map { rs, _ -> CatalogPublicationStatus.valueOf(rs.getString("status")) to rs.getInt("total") }
            .list().toMap()
    }

    /** The tenant's most recently touched publications across every catalog. */
    fun recent(tenantKey: TenantKey, limit: Int): List<CatalogReleasePublication> = jdbi.withHandle<List<CatalogReleasePublication>, Exception> { handle ->
        handle.createQuery("$SELECT_METADATA WHERE tenant_key = :tenantKey ORDER BY updated_at DESC LIMIT :limit")
            .bind("tenantKey", tenantKey).bind("limit", limit).map(::map).list()
    }

    /**
     * The tenant's longest-outstanding unfinished publication, or null if none is outstanding.
     * This is the "is anything stuck?" signal: waiting is normal, waiting for days is not, and
     * nothing else in the model distinguishes the two.
     *
     * The age is computed in the database because `created_at` is a database-owned timestamp —
     * ageing it against the application clock would compare two different clocks.
     */
    fun oldestActive(tenantKey: TenantKey): OldestActivePublication? = jdbi.withHandle<OldestActivePublication?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT min(created_at) AS oldest,
                   EXTRACT(EPOCH FROM (NOW() - min(created_at))) AS age_seconds
            FROM catalog_release_publications
            WHERE tenant_key = :tenantKey AND status = ANY(:active)
            """,
        ).bind("tenantKey", tenantKey).bind("active", activeNames()).map { rs, _ ->
            rs.getObject("oldest", OffsetDateTime::class.java)
                ?.let { OldestActivePublication(it, Duration.ofSeconds(rs.getLong("age_seconds"))) }
        }.findOne().orElse(null)
    }

    /** Installation-wide counts per state, for the leader-published gauges. */
    fun installationCountsByStatus(): Map<CatalogPublicationStatus, Long> = jdbi.withHandle<Map<CatalogPublicationStatus, Long>, Exception> { handle ->
        handle.createQuery("SELECT status, count(*) AS total FROM catalog_release_publications GROUP BY status")
            .map { rs, _ -> CatalogPublicationStatus.valueOf(rs.getString("status")) to rs.getLong("total") }
            .list().toMap()
    }

    /** Installation-wide age of the oldest unfinished publication, in seconds; 0 when there is none. */
    fun installationOldestActiveAgeSeconds(): Double = jdbi.withHandle<Double, Exception> { handle ->
        handle.createQuery(
            """
            SELECT COALESCE(EXTRACT(EPOCH FROM (NOW() - min(created_at))), 0) AS age
            FROM catalog_release_publications WHERE status = ANY(:active)
            """,
        ).bind("active", activeNames()).mapTo(Double::class.java).one()
    }

    /**
     * Fails every still-active publication for a tenant. Used when the thing they were waiting on
     * is gone for good — currently a disconnect. `FAILED` is terminal for the worker but keeps the
     * retained archive, so the administrator's normal retry still applies after reconnecting.
     */
    fun abandonActive(handle: Handle, tenantKey: TenantKey, reason: String): Int = handle.createUpdate(
        """
        UPDATE catalog_release_publications
        SET status = :failed, last_error = :reason, claimed_at = NULL, updated_at = NOW()
        WHERE tenant_key = :tenantKey AND status = ANY(:active)
        """,
    ).bind("failed", CatalogPublicationStatus.FAILED).bind("reason", reason)
        .bind("tenantKey", tenantKey)
        .bind("active", activeNames())
        .execute()

    private fun activeNames() = CatalogPublicationStatus.active.map(CatalogPublicationStatus::name).toTypedArray()

    private fun map(rs: ResultSet, _context: StatementContext) = CatalogReleasePublication(
        id = rs.getObject("id", UUID::class.java),
        tenantKey = TenantKey.of(rs.getString("tenant_key")),
        catalogKey = CatalogKey.of(rs.getString("catalog_key")),
        version = rs.getString("version"),
        namespace = rs.getString("namespace"),
        status = CatalogPublicationStatus.valueOf(rs.getString("status")),
        idempotencyKey = rs.getObject("idempotency_key", UUID::class.java),
        remotePublicationId = rs.getObject("remote_publication_id", UUID::class.java),
        attempts = rs.getInt("attempts"),
        archiveRetained = rs.getBoolean("archive_retained"),
        lastError = rs.getString("last_error"),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
        updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java),
    )

    private companion object {
        /** Never selects `archive`; only whether one is still retained. */
        const val SELECT_METADATA = """
            SELECT id, tenant_key, catalog_key, version, namespace, status, idempotency_key,
                   remote_publication_id, attempts, archive IS NOT NULL AS archive_retained,
                   last_error, created_at, updated_at
            FROM catalog_release_publications
        """
    }
}
