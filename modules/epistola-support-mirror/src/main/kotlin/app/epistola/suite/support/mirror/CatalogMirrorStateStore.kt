// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.mirror

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.support.connection.SupportHttp
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/** What a round recorded for a tenant whose set the support service accepted. */
data class CatalogMirrorRoundOutcome(
    val digest: String,
    /** The catalogs (`catalogs.catalog_id`) whose releases were in the accepted set. */
    val syncedCatalogs: Set<java.util.UUID>,
    val bindingsSent: Int,
    val releasesUploaded: Int,
    val failedReleases: List<CatalogMirrorFailedRelease>,
)

/**
 * The reconcile's bookkeeping in `support_mirror_state`: per-tenant claims, so a scheduled round and
 * a "Sync now" never reconcile one tenant at once, and what each round achieved. Every method is one
 * short transaction of its own; none is ever held open across a call to the support service.
 * Application time throughout ([now] parameters), so tests can move it — except the claims: their
 * lease is stamped and compared by the database's clock, the one clock every node shares, so a node
 * whose own clock is off neither takes over a live round nor holds a dead one's claim longer.
 */
@Component
class CatalogMirrorStateStore(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    supportHttp: SupportHttp,
) {
    /**
     * A claim on a tenant older than this is taken to belong to a node that died, and another round
     * takes the tenant over. A live round renews its claim after every upload, so the lease only has
     * to outlast the longest stretch between two renewals: one release exported and uploaded, after
     * the set was sent. That upload may take the deadline of one call for the largest archive the
     * mirror takes (`epistola.support.read-timeout` plus a second per 512 KiB, [SupportHttp.deadline]),
     * so the lease is that plus [CatalogMirror.CLAIM_LEASE_MARGIN]: 5 minutes 10 seconds with the
     * default 30-second read-timeout, longer when an installation on a slow link raises it. A round
     * that did outlive its lease stops at its next renewal and leaves the tenant to the round that
     * took it over.
     */
    val claimLease: Duration = supportHttp.deadline(CatalogMirror.MAX_ARCHIVE_BYTES.toInt()).plus(CatalogMirror.CLAIM_LEASE_MARGIN)

    /**
     * Claims [tenantKey] for this round: its claim stamp when no other round holds it, or its claim
     * is older than [claimLease] by the database's clock; null otherwise, and for a
     * tenant that no longer exists. The round renews it ([renew]) as it goes and lets go only of its
     * own ([release]).
     */
    fun claim(connectionId: UUID, tenantKey: TenantKey): OffsetDateTime? = jdbi.withHandle<OffsetDateTime?, Exception> { handle ->
        handle.createQuery(
            """
            INSERT INTO support_mirror_state (connection_id, tenant_key, claimed_at)
            SELECT :connection, :t, clock_timestamp() WHERE EXISTS (SELECT 1 FROM tenants WHERE id = :t)
            ON CONFLICT (connection_id, tenant_key) DO UPDATE SET claimed_at = EXCLUDED.claimed_at
            WHERE support_mirror_state.claimed_at IS NULL
               OR support_mirror_state.claimed_at < clock_timestamp() - make_interval(secs => :leaseSeconds)
            RETURNING claimed_at
            """,
        )
            .bind("t", tenantKey.value)
            .bind("connection", connectionId)
            .bind("leaseSeconds", claimLease.seconds)
            .mapTo(OffsetDateTime::class.java)
            .findOne()
            .orElse(null)
    }

    /**
     * Renews the claim stamped [claimedAt] to the database's time now: the new stamp, or null when
     * another round took the tenant over meanwhile, after which this round must stop.
     */
    fun renew(connectionId: UUID, tenantKey: TenantKey, claimedAt: OffsetDateTime): OffsetDateTime? = jdbi.withHandle<OffsetDateTime?, Exception> { handle ->
        handle.createQuery(
            """
            UPDATE support_mirror_state SET claimed_at = GREATEST(clock_timestamp(), claimed_at + interval '1 microsecond')
            WHERE connection_id = :connection AND tenant_key = :t AND claimed_at = :mine
            RETURNING claimed_at
            """,
        )
            .bind("t", tenantKey.value)
            .bind("connection", connectionId)
            .bind("mine", claimedAt)
            .mapTo(OffsetDateTime::class.java)
            .findOne()
            .orElse(null)
    }

    /** A round holds [tenantKey] now: a claim within [claimLease], by the database's clock. */
    fun isClaimed(connectionId: UUID, tenantKey: TenantKey): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createQuery(
            """
            SELECT EXISTS (
                SELECT 1 FROM support_mirror_state
                WHERE connection_id = :connection AND tenant_key = :t AND claimed_at >= clock_timestamp() - make_interval(secs => :leaseSeconds)
            )
            """,
        )
            .bind("t", tenantKey.value)
            .bind("connection", connectionId)
            .bind("leaseSeconds", claimLease.seconds)
            .mapTo(Boolean::class.java)
            .one()
    }

    /** The tenant exists in this installation (a claim it cannot get is then held by another round). */
    fun exists(tenantKey: TenantKey): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createQuery("SELECT EXISTS (SELECT 1 FROM tenants WHERE id = :t)")
            .bind("t", tenantKey.value)
            .mapTo(Boolean::class.java)
            .one()
    }

    /** The tenant is opted in and has not opted out: read again during a round, so an opt-out stops it. */
    fun shares(connectionId: UUID, tenantKey: TenantKey): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createQuery("SELECT EXISTS (SELECT 1 FROM support_sharing_choice WHERE ${mirrorSharing()} AND tenant_key = :t AND withdrawn_at IS NULL)")
            .bind("t", tenantKey.value)
            .bind("connection", connectionId)
            .mapTo(Boolean::class.java)
            .one()
    }

    /**
     * The tenant still sends the release [versionRowId]: it is shared, or a shared release depends on
     * it, and nothing it depends on is never shared ([CatalogMirror.SENT_RELEASES_SQL], the same rule
     * as [ListMirrorBindings]), read again right before the release is exported and sent.
     */
    fun stillSends(connectionId: UUID, tenantKey: TenantKey, versionRowId: java.util.UUID): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createQuery("${CatalogMirror.SENT_RELEASES_SQL} SELECT EXISTS (SELECT 1 FROM sent WHERE version_id = :v)")
            .bind("t", tenantKey.value)
            .bind("connection", connectionId)
            .bind("v", versionRowId)
            .mapTo(Boolean::class.java)
            .one()
    }

    /**
     * Lets go of the claim stamped [claimedAt] and clears a "Sync now" request, which the round just
     * ended answered. A claim another round took over meanwhile is left alone.
     */
    fun release(connectionId: UUID, tenantKey: TenantKey, claimedAt: OffsetDateTime) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate("UPDATE support_mirror_state SET claimed_at = NULL, sync_requested_at = NULL WHERE connection_id = :connection AND tenant_key = :t AND claimed_at = :mine")
            .bind("t", tenantKey.value)
            .bind("connection", connectionId)
            .bind("mine", claimedAt)
            .execute()
    }

    /** Drops a "Sync now" request that cannot be served, leaving any claim alone. */
    fun clearSyncRequest(connectionId: UUID, tenantKey: TenantKey) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate("UPDATE support_mirror_state SET sync_requested_at = NULL WHERE connection_id = :connection AND tenant_key = :t")
            .bind("t", tenantKey.value)
            .bind("connection", connectionId)
            .execute()
    }

    fun round(connectionId: UUID, tenantKey: TenantKey): CatalogMirrorRound? = jdbi.withHandle<CatalogMirrorRound?, Exception> { round(it, connectionId, tenantKey, objectMapper) }

    /** The digest of the set last accepted. */
    fun lastDigest(connectionId: UUID, tenantKey: TenantKey): String? = jdbi.withHandle<String?, Exception> { handle ->
        handle.createQuery("SELECT last_digest FROM support_mirror_state WHERE connection_id = :connection AND tenant_key = :t")
            .bind("t", tenantKey.value)
            .bind("connection", connectionId)
            .mapTo(String::class.java)
            .findOne()
            .orElse(null)
    }

    /** The support service accepted the tenant's set; [outcome] says what was uploaded and what failed. */
    fun recordAccepted(connectionId: UUID, tenantKey: TenantKey, outcome: CatalogMirrorRoundOutcome, now: OffsetDateTime) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate(
            """
            UPDATE support_mirror_state SET
                last_attempt_at = :now, last_success_at = :now, last_digest = :digest,
                bindings_sent = :bindings, releases_uploaded = :uploaded, releases_missing = :missing,
                failed_releases = CAST(:failed AS JSONB), synced_catalogs = CAST(:synced AS JSONB),
                consecutive_failures = 0, next_attempt_at = NULL,
                last_error = :error
            WHERE connection_id = :connection AND tenant_key = :t
            """,
        )
            .bind("t", tenantKey.value)
            .bind("connection", connectionId)
            .bind("now", now)
            .bind("digest", outcome.digest)
            .bind("bindings", outcome.bindingsSent)
            .bind("uploaded", outcome.releasesUploaded)
            .bind("missing", outcome.failedReleases.size)
            .bind("failed", objectMapper.writeValueAsString(outcome.failedReleases.take(MAX_FAILED_RELEASES)))
            .bind("synced", objectMapper.writeValueAsString(outcome.syncedCatalogs.map { it.toString() }.sorted()))
            .bind("error", outcome.failedReleases.firstOrNull()?.let { "${outcome.failedReleases.size} release(s) not uploaded: ${it.error}" })
            .execute()
    }

    /** The round for the tenant did not get its set accepted; it is not tried again before [nextAttemptAt]. */
    fun recordFailure(connectionId: UUID, tenantKey: TenantKey, error: String, now: OffsetDateTime, nextAttemptAt: OffsetDateTime) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate(
            """
            UPDATE support_mirror_state SET
                last_attempt_at = :now, consecutive_failures = consecutive_failures + 1,
                next_attempt_at = :next, last_error = :error
            WHERE connection_id = :connection AND tenant_key = :t
            """,
        )
            .bind("t", tenantKey.value)
            .bind("connection", connectionId)
            .bind("now", now)
            .bind("next", nextAttemptAt)
            .bind("error", error.take(MAX_ERROR_LENGTH))
            .execute()
    }

    /** How many rounds in a row failed for the tenant. */
    fun consecutiveFailures(connectionId: UUID, tenantKey: TenantKey): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery("SELECT consecutive_failures FROM support_mirror_state WHERE connection_id = :connection AND tenant_key = :t")
            .bind("t", tenantKey.value)
            .bind("connection", connectionId)
            .mapTo(Int::class.java)
            .findOne()
            .orElse(0)
    }

    /**
     * The support service confirmed it holds nothing of the tenant any more: the opt-out, when there
     * was one, is confirmed, and the state starts over for a later opt-in.
     */
    fun recordWithdrawn(connectionId: UUID, tenantKey: TenantKey, now: OffsetDateTime) = jdbi.useTransaction<Exception> { handle ->
        handle.createUpdate(
            """
            UPDATE support_sharing_choice SET withdrawal_confirmed_at = :now
            WHERE ${mirrorSharing()} AND tenant_key = :t AND withdrawn_at IS NOT NULL AND withdrawal_confirmed_at IS NULL
            """,
        )
            .bind("t", tenantKey.value)
            .bind("connection", connectionId)
            .bind("now", now)
            .execute()
        handle.createUpdate(
            """
            UPDATE support_mirror_state SET
                last_attempt_at = :now, last_success_at = :now, last_digest = NULL, bindings_sent = 0,
                releases_uploaded = 0, releases_missing = 0, failed_releases = '[]'::jsonb, synced_catalogs = '[]'::jsonb,
                consecutive_failures = 0, next_attempt_at = NULL, last_error = NULL
            WHERE connection_id = :connection AND tenant_key = :t
            """,
        )
            .bind("t", tenantKey.value)
            .bind("connection", connectionId)
            .bind("now", now)
            .execute()
    }

    companion object {
        private const val MAX_FAILED_RELEASES = 50
        private const val MAX_ERROR_LENGTH = 2_000

        fun round(handle: Handle, connectionId: UUID, tenantKey: TenantKey, objectMapper: ObjectMapper): CatalogMirrorRound? = handle.createQuery(
            """
            SELECT last_attempt_at, last_success_at, bindings_sent, releases_uploaded, releases_missing, failed_releases::text AS failed,
                   consecutive_failures, next_attempt_at, last_error, sync_requested_at
            FROM support_mirror_state WHERE connection_id = :connection AND tenant_key = :t
            """,
        )
            .bind("t", tenantKey.value)
            .bind("connection", connectionId)
            .map { rs, _ ->
                CatalogMirrorRound(
                    lastAttemptAt = rs.getObject("last_attempt_at", OffsetDateTime::class.java),
                    lastSuccessAt = rs.getObject("last_success_at", OffsetDateTime::class.java),
                    bindingsSent = rs.getInt("bindings_sent"),
                    releasesUploaded = rs.getInt("releases_uploaded"),
                    releasesMissing = rs.getInt("releases_missing"),
                    failedReleases = objectMapper.readTree(rs.getString("failed")).iterator().asSequence().map { node ->
                        CatalogMirrorFailedRelease(
                            node.path("contentHash").asString(),
                            node.path("error").asString(),
                            node.path("attempts").asInt(1),
                            node.path("nextAttemptAt").takeIf { it.isString }?.let { runCatching { OffsetDateTime.parse(it.asString()) }.getOrNull() },
                        )
                    }.toList(),
                    consecutiveFailures = rs.getInt("consecutive_failures"),
                    nextAttemptAt = rs.getObject("next_attempt_at", OffsetDateTime::class.java),
                    lastError = rs.getString("last_error"),
                    syncRequestedAt = rs.getObject("sync_requested_at", OffsetDateTime::class.java),
                )
            }
            .findOne()
            .orElse(null)
    }
}
