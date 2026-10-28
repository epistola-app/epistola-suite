// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.common.ids.CatalogId
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
    private val metrics: CatalogMetrics,
) {
    /** Every recorded check for a tenant, by catalog (namespace and key, ADR 0034), for rendering a whole list at once. */
    fun statesFor(tenantKey: TenantKey): Map<CatalogId, CatalogUpstreamCheck> = jdbi.withHandle<Map<CatalogId, CatalogUpstreamCheck>, Exception> { handle ->
        handle.createQuery("$SELECT_CHECK WHERE u.tenant_key = :t")
            .bind("t", tenantKey)
            .map { rs, ctx -> CatalogId(rs.getString("namespace"), CatalogKey.of(rs.getString("catalog_key")), app.epistola.suite.common.ids.TenantId(tenantKey)) to map(rs, ctx) }
            .list()
            .toMap()
    }

    fun stateFor(catalog: CatalogId): CatalogUpstreamCheck? = jdbi.withHandle<CatalogUpstreamCheck?, Exception> { handle ->
        handle.createQuery("$SELECT_CHECK WHERE u.tenant_key = :t AND u.catalog_id = ${catalogAt("t", "c")}")
            .bind("t", catalog.tenantKey).bindCatalog("c", catalog)
            .map(::map).findOne().orElse(null)
    }

    /**
     * How many of a tenant's catalogs have an upgrade waiting.
     *
     * Rendered in the navigation on every page for every signed-in user, so it stays one indexed
     * count over already-recorded state — never a call to anybody's server.
     */
    fun countUpgradesAvailable(tenantKey: TenantKey): Int = jdbi.withHandle<Int, Exception> { handle ->
        // One indexed read of the offers that differ from the catalog's highest release; which of
        // them are newer is decided by the one definition of that ([CatalogUpstreamCheck.upgradeAvailable]).
        handle.createQuery(
            """
            SELECT u.available_release_version, cr.version FROM catalog_upstream_checks u
            LEFT JOIN catalog_current_releases cr ON cr.tenant_key = u.tenant_key AND cr.catalog_id = u.catalog_id
            WHERE u.tenant_key = :t
              AND u.available_release_version IS NOT NULL
              AND u.available_release_version IS DISTINCT FROM cr.version
            """,
        ).bind("t", tenantKey)
            .map { rs, _ -> SemVer.isNewer(rs.getString(1), rs.getString(2)) }
            .list()
            .count { it }
    }

    /**
     * Starts tracking a catalog, or resets tracking after it is installed or upgraded.
     *
     * [archiveSha256] is the digest of the bytes actually imported — not the catalog's content
     * fingerprint, which is a different thing computed a different way. Null for sources that do
     * not serve an archive.
     */
    fun recordInstalled(catalog: CatalogId, version: String, archiveSha256: String?) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO catalog_upstream_checks (
                    tenant_key, catalog_id, available_release_version, upstream_availability,
                    upstream_availability_checked_at, received_archive_sha256,
                    last_checked_at, next_check_at, check_attempts, error_code, error_detail
                )
                VALUES (:t, ${catalogAt("t", "c")}, :version, 'AVAILABLE', NOW(), :sha, NOW(), NOW() + :interval * INTERVAL '1 second', 0, NULL, NULL)
                ON CONFLICT (tenant_key, catalog_id) DO UPDATE
                SET available_release_version = :version,
                    upstream_availability = 'AVAILABLE',
                    upstream_availability_checked_at = NOW(),
                    received_archive_sha256 = :sha,
                    last_checked_at = NOW(),
                    next_check_at = NOW() + :interval * INTERVAL '1 second',
                    check_attempts = 0,
                    error_code = NULL,
                    error_detail = NULL,
                    updated_at = NOW()
                """,
            )
                .bind("t", catalog.tenantKey).bindCatalog("c", catalog)
                .bind("version", version).bind("sha", archiveSha256)
                .bind("interval", DEFAULT_INTERVAL_SECONDS)
                .execute()
        }
    }

    /**
     * Makes sure a catalog is tracked, without disturbing an answer already recorded.
     *
     * Catalogs subscribed before this existed have no row, and the on-demand check must be able to
     * create one. `DO NOTHING` rather than an upsert: an existing row's cadence and last answer are
     * the point, and resetting them because somebody pressed a button would lose exactly what the
     * button is meant to refresh.
     */
    fun ensureTracked(catalog: CatalogId) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO catalog_upstream_checks (tenant_key, catalog_id)
                VALUES (:t, ${catalogAt("t", "c")})
                ON CONFLICT (tenant_key, catalog_id) DO NOTHING
                """,
            ).bind("t", catalog.tenantKey).bindCatalog("c", catalog).execute()
        }
    }

    /**
     * Claims catalogs whose next check is due, leasing them so a second node steps over them.
     *
     * `FOR UPDATE SKIP LOCKED` plus an expiring `claimed_at` rather than relying on the task being
     * single-owner: a check that is merely duplicated wastes a request, but a node that dies
     * holding claims must not stop its catalogs being checked ever again.
     */
    fun claimDue(limit: Int, lease: Duration): List<Catalog> = jdbi.inTransaction<List<Catalog>, Exception> { handle ->
        // The catalog itself is returned rather than its key: a probe needs the source and the
        // installed version anyway, and reading those through the permission-gated query would need
        // the scheduler to hold a principal it has no business holding.
        val rows = handle.createQuery(
            """
                SELECT $CATALOG_ROW_COLUMNS
                FROM catalog_upstream_checks u
                JOIN catalogs c ON c.tenant_key = u.tenant_key AND c.catalog_id = u.catalog_id
                $CATALOG_RELEASE_JOIN
                WHERE u.next_check_at <= NOW()
                  AND c.source_url IS NOT NULL
                  AND (u.claimed_at IS NULL OR u.claimed_at < NOW() - :lease * INTERVAL '1 second')
                ORDER BY u.next_check_at
                FOR UPDATE OF u SKIP LOCKED
                LIMIT :limit
                """,
        ).bind("lease", lease.toSeconds()).bind("limit", limit)
            .mapTo(Catalog::class.java).list()
        rows.forEach { catalog ->
            handle.createUpdate(
                "UPDATE catalog_upstream_checks SET claimed_at = NOW() WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}",
            ).bind("t", catalog.tenantKey).bindCatalog("c", catalog.catalogId).execute()
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
        catalog: CatalogId,
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
                    upstream_availability = COALESCE(:availability, upstream_availability),
                    upstream_availability_checked_at = CASE
                        WHEN :availability IS NULL THEN upstream_availability_checked_at ELSE NOW() END,
                    last_checked_at = NOW(),
                    next_check_at = $JITTERED_NEXT_CHECK,
                    check_attempts = 0,
                    claimed_at = NULL,
                    error_code = NULL,
                    error_detail = NULL,
                    updated_at = NOW()
                WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}
                """,
            )
                .bind("t", catalog.tenantKey).bindCatalog("c", catalog)
                .bind("version", state.availableVersion)
                .bind("schemaVersion", state.availableSchemaVersion)
                .bind("publishedAt", state.availablePublishedAt)
                .bind("availability", state.upstreamAvailability?.name)
                .bind("interval", interval.toSeconds())
                .execute()
        }
        metrics.upstreamChecked(CatalogMetrics.OUTCOME_OK)
    }

    /**
     * Records why a check produced nothing, and slows it down.
     *
     * There is no attempt limit, unlike the publication outbox. Giving up would quietly stop telling
     * anyone about upgrades, which is the one thing this exists to do — so a persistently failing
     * check keeps retrying on a long backoff and says so on the catalog instead.
     */
    fun recordFailure(
        catalog: CatalogId,
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
                WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}
                """,
            )
                .bind("t", catalog.tenantKey).bindCatalog("c", catalog)
                .bind("code", failure.name).bind("detail", detail?.take(MAX_DETAIL))
                .bind("interval", interval.toSeconds())
                .execute()
        }
        metrics.upstreamChecked(failure.name.lowercase())
    }

    private fun map(rs: ResultSet, ctx: StatementContext) = CatalogUpstreamCheck(
        catalogKey = CatalogKey.of(rs.getString("catalog_key")),
        availableVersion = rs.getString("available_release_version"),
        availableSchemaVersion = rs.getObject("available_schema_version") as Int?,
        upstreamAvailability = rs.getString("upstream_availability")?.let(UpstreamAvailability::valueOf),
        receivedArchiveSha256 = rs.getString("received_archive_sha256"),
        lastCheckedAt = rs.getObject("last_checked_at", OffsetDateTime::class.java),
        failure = rs.getString("error_code")?.let { code ->
            runCatching { CatalogUpstreamCheckFailure.valueOf(code) }.getOrNull()
        },
        failureDetail = rs.getString("error_detail"),
    )

    private companion object {
        const val SELECT_CHECK = """
            SELECT c.catalog_key, c.namespace, u.available_release_version, u.available_schema_version,
                   u.upstream_availability, u.received_archive_sha256, u.last_checked_at,
                   u.error_code, u.error_detail
            FROM catalog_upstream_checks u
            JOIN catalogs c ON c.tenant_key = u.tenant_key AND c.catalog_id = u.catalog_id
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

/** The last recorded answer for one catalog, as the UI reads it. */
data class CatalogUpstreamCheck(
    val catalogKey: CatalogKey,
    val availableVersion: String?,
    val availableSchemaVersion: Int?,
    val upstreamAvailability: UpstreamAvailability?,
    val receivedArchiveSha256: String?,
    val lastCheckedAt: OffsetDateTime?,
    val failure: CatalogUpstreamCheckFailure?,
    val failureDetail: String?,
) {
    /**
     * Whether the recorded answer names a release newer than [latestVersion], the catalog's highest
     * release ([SemVer.isNewer]): one cut here above its source's keeps it current.
     */
    fun upgradeAvailable(latestVersion: String?): Boolean = availableVersion != null && SemVer.isNewer(availableVersion, latestVersion)
}
