// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.statement.StatementContext
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Duration
import java.time.OffsetDateTime

/**
 * What a subscribed catalog's source was last seen to offer, and when to ask again.
 *
 * Sole owner of the `catalog_upstream_checks` SQL, the way `CatalogPublicationStore` owns the
 * outbox: claiming, the cadence, and the recorded reason all have one definition to change.
 */
@Component
class CatalogUpstreamCheckStore(
    private val jdbi: Jdbi,
) {
    /** Every recorded check for a tenant, keyed by catalog, for rendering a whole list at once. */
    fun statesFor(tenantKey: TenantKey): Map<CatalogKey, CatalogUpstreamCheck> = jdbi.withHandle<Map<CatalogKey, CatalogUpstreamCheck>, Exception> { handle ->
        handle.createQuery("$SELECT_CHECK WHERE tenant_key = :t")
            .bind("t", tenantKey)
            .map(::map)
            .list()
            .associateBy(CatalogUpstreamCheck::catalogKey)
    }

    fun stateFor(tenantKey: TenantKey, catalogKey: CatalogKey): CatalogUpstreamCheck? = jdbi.withHandle<CatalogUpstreamCheck?, Exception> { handle ->
        handle.createQuery("$SELECT_CHECK WHERE tenant_key = :t AND catalog_key = :c")
            .bind("t", tenantKey).bind("c", catalogKey)
            .map(::map).findOne().orElse(null)
    }

    /**
     * How many of a tenant's catalogs have an upgrade waiting.
     *
     * Rendered in the navigation on every page for every signed-in user, so it stays one indexed
     * count over already-recorded state — never a call to anybody's server.
     */
    fun countUpgradesAvailable(tenantKey: TenantKey): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery(
            """
            SELECT COUNT(*) FROM catalog_upstream_checks u
            JOIN catalogs c ON c.tenant_key = u.tenant_key AND c.id = u.catalog_key
            WHERE u.tenant_key = :t
              AND u.available_release_version IS NOT NULL
              AND u.available_release_version IS DISTINCT FROM c.installed_release_version
            """,
        ).bind("t", tenantKey).mapTo(Int::class.java).one()
    }

    /**
     * Starts tracking a catalog, or resets tracking after it is installed or upgraded.
     *
     * [archiveSha256] is the digest of the bytes actually imported — not the catalog's content
     * fingerprint, which is a different thing computed a different way. Null for sources that do
     * not serve an archive.
     */
    fun recordInstalled(tenantKey: TenantKey, catalogKey: CatalogKey, version: String, archiveSha256: String?) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO catalog_upstream_checks (
                    tenant_key, catalog_key, available_release_version, installed_availability,
                    installed_availability_checked_at, installed_archive_sha256,
                    last_checked_at, next_check_at, check_attempts, error_code, error_detail
                )
                VALUES (:t, :c, :version, 'AVAILABLE', NOW(), :sha, NOW(), NOW() + :interval * INTERVAL '1 second', 0, NULL, NULL)
                ON CONFLICT (tenant_key, catalog_key) DO UPDATE
                SET available_release_version = :version,
                    installed_availability = 'AVAILABLE',
                    installed_availability_checked_at = NOW(),
                    installed_archive_sha256 = :sha,
                    last_checked_at = NOW(),
                    next_check_at = NOW() + :interval * INTERVAL '1 second',
                    check_attempts = 0,
                    error_code = NULL,
                    error_detail = NULL,
                    updated_at = NOW()
                """,
            )
                .bind("t", tenantKey).bind("c", catalogKey)
                .bind("version", version).bind("sha", archiveSha256)
                .bind("interval", DEFAULT_INTERVAL_SECONDS)
                .execute()
        }
    }

    /**
     * Claims catalogs whose next check is due, leasing them so a second node steps over them.
     *
     * `FOR UPDATE SKIP LOCKED` plus an expiring `claimed_at` rather than relying on the task being
     * single-owner: a check that is merely duplicated wastes a request, but a node that dies
     * holding claims must not stop its catalogs being checked ever again.
     */
    fun claimDue(limit: Int, lease: Duration): List<CatalogUpstreamCheckClaim> = jdbi.inTransaction<List<CatalogUpstreamCheckClaim>, Exception> { handle ->
        val rows = handle.createQuery(
            """
                SELECT u.tenant_key, u.catalog_key
                FROM catalog_upstream_checks u
                JOIN catalogs c ON c.tenant_key = u.tenant_key AND c.id = u.catalog_key
                WHERE u.next_check_at <= NOW()
                  AND c.source_url IS NOT NULL
                  AND (u.claimed_at IS NULL OR u.claimed_at < NOW() - :lease * INTERVAL '1 second')
                ORDER BY u.next_check_at
                FOR UPDATE OF u SKIP LOCKED
                LIMIT :limit
                """,
        ).bind("lease", lease.toSeconds()).bind("limit", limit)
            .map { rs, _ ->
                CatalogUpstreamCheckClaim(
                    TenantKey.of(rs.getString("tenant_key")),
                    CatalogKey.of(rs.getString("catalog_key")),
                )
            }.list()
        rows.forEach { claim ->
            handle.createUpdate(
                "UPDATE catalog_upstream_checks SET claimed_at = NOW() WHERE tenant_key = :t AND catalog_key = :c",
            ).bind("t", claim.tenantKey).bind("c", claim.catalogKey).execute()
        }
        rows
    }

    /**
     * Records what a source said, and when to ask it again.
     *
     * The whole reason is replaced as a unit — a code from one failure carrying the detail of
     * another describes a state that never happened.
     */
    fun recordSuccess(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        state: CatalogUpstreamState,
        interval: Duration,
    ) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE catalog_upstream_checks
                SET available_release_version = :version,
                    available_schema_version = :schemaVersion,
                    available_published_at = :publishedAt,
                    installed_availability = COALESCE(:availability, installed_availability),
                    installed_availability_checked_at = CASE
                        WHEN :availability IS NULL THEN installed_availability_checked_at ELSE NOW() END,
                    last_checked_at = NOW(),
                    next_check_at = $JITTERED_NEXT_CHECK,
                    check_attempts = 0,
                    claimed_at = NULL,
                    error_code = NULL,
                    error_detail = NULL,
                    updated_at = NOW()
                WHERE tenant_key = :t AND catalog_key = :c
                """,
            )
                .bind("t", tenantKey).bind("c", catalogKey)
                .bind("version", state.availableVersion)
                .bind("schemaVersion", state.availableSchemaVersion)
                .bind("publishedAt", state.availablePublishedAt)
                .bind("availability", state.installedAvailability?.name)
                .bind("interval", interval.toSeconds())
                .execute()
        }
    }

    /**
     * Records why a check produced nothing, and slows it down.
     *
     * There is no attempt limit, unlike the publication outbox. Giving up would quietly stop telling
     * anyone about upgrades, which is the one thing this exists to do — so a persistently failing
     * check keeps retrying on a long backoff and says so on the catalog instead.
     */
    fun recordFailure(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        failure: CatalogUpstreamCheckFailure,
        detail: String?,
        interval: Duration,
    ) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE catalog_upstream_checks
                SET last_checked_at = NOW(),
                    check_attempts = check_attempts + 1,
                    next_check_at = NOW() + LEAST(
                        :interval,
                        GREATEST($MIN_BACKOFF_SECONDS, POWER(2, LEAST(check_attempts, $MAX_BACKOFF_SHIFT)) * $MIN_BACKOFF_SECONDS)
                    ) * INTERVAL '1 second',
                    claimed_at = NULL,
                    error_code = :code,
                    error_detail = :detail,
                    updated_at = NOW()
                WHERE tenant_key = :t AND catalog_key = :c
                """,
            )
                .bind("t", tenantKey).bind("c", catalogKey)
                .bind("code", failure.name).bind("detail", detail?.take(MAX_DETAIL))
                .bind("interval", interval.toSeconds())
                .execute()
        }
    }

    private fun map(rs: ResultSet, ctx: StatementContext) = CatalogUpstreamCheck(
        catalogKey = CatalogKey.of(rs.getString("catalog_key")),
        availableVersion = rs.getString("available_release_version"),
        availableSchemaVersion = rs.getObject("available_schema_version") as Int?,
        installedAvailability = rs.getString("installed_availability")?.let(UpstreamAvailability::valueOf),
        installedArchiveSha256 = rs.getString("installed_archive_sha256"),
        lastCheckedAt = rs.getObject("last_checked_at", OffsetDateTime::class.java),
        failure = rs.getString("error_code")?.let { code ->
            runCatching { CatalogUpstreamCheckFailure.valueOf(code) }.getOrNull()
        },
        failureDetail = rs.getString("error_detail"),
    )

    private companion object {
        const val SELECT_CHECK = """
            SELECT catalog_key, available_release_version, available_schema_version,
                   installed_availability, installed_archive_sha256, last_checked_at,
                   error_code, error_detail
            FROM catalog_upstream_checks
        """

        /**
         * Spread the next check across ±15% of the interval.
         *
         * Twenty catalogs installed in one afternoon would otherwise come due together for as long
         * as they exist, turning a routine check into a burst against the same source every time.
         */
        const val JITTERED_NEXT_CHECK = "NOW() + (:interval * (0.85 + random() * 0.30)) * INTERVAL '1 second'"

        const val MIN_BACKOFF_SECONDS = 60
        const val MAX_BACKOFF_SHIFT = 6
        const val MAX_DETAIL = 2000

        /** Until the worker sets a cadence of its own, a freshly installed catalog waits a day. */
        const val DEFAULT_INTERVAL_SECONDS = 86400
    }
}

/** One catalog the worker has taken responsibility for checking. */
data class CatalogUpstreamCheckClaim(val tenantKey: TenantKey, val catalogKey: CatalogKey)

/** The last recorded answer for one catalog, as the UI reads it. */
data class CatalogUpstreamCheck(
    val catalogKey: CatalogKey,
    val availableVersion: String?,
    val availableSchemaVersion: Int?,
    val installedAvailability: UpstreamAvailability?,
    val installedArchiveSha256: String?,
    val lastCheckedAt: OffsetDateTime?,
    val failure: CatalogUpstreamCheckFailure?,
    val failureDetail: String?,
) {
    /** Whether the recorded answer names a release other than the one installed. */
    fun upgradeAvailable(installedVersion: String?): Boolean = availableVersion != null && availableVersion != installedVersion
}
