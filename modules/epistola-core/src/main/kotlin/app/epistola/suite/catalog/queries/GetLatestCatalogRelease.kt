// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Cheap read of an AUTHORED catalog's release pointer straight from
 * `catalog_releases` — **no catalog content build / fingerprint recompute**.
 *
 * Split out of [GetCatalogReleaseStatus] so callers that only need the latest
 * released version/fingerprint (notably [ExportCatalogZip][app.epistola.suite.catalog.commands.ExportCatalogZip])
 * don't pay an O(catalog-size) content build. The drift-aware
 * [GetCatalogReleaseStatus] delegates here and adds the working-copy compare.
 */
data class GetLatestCatalogRelease(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<LatestCatalogRelease>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

data class SuggestedBumps(
    val patch: String,
    val minor: String,
    val major: String,
)

data class LatestCatalogRelease(
    val latestVersion: String?,
    val latestFingerprint: String?,
    val suggestedNext: SuggestedBumps,
)

/**
 * Newest release of a catalog first.
 *
 * On the generated version components (V20260923150918): a version sorts by its parts, not as text.
 * Labels that are not MAJOR.MINOR.PATCH have null components — `SemVer.parseOrNull` tolerates them
 * — so they sort last and fall back to when they were released.
 */
internal const val LATEST_RELEASE_ORDER = """
    ORDER BY version_major DESC NULLS LAST, version_minor DESC NULLS LAST,
             version_patch DESC NULLS LAST, released_at DESC
"""

@Component
class GetLatestCatalogReleaseHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetLatestCatalogRelease, LatestCatalogRelease> {

    private data class Row(val version: String, val fingerprint: String)

    override fun handle(query: GetLatestCatalogRelease): LatestCatalogRelease {
        // Ordered in SQL ([LATEST_RELEASE_ORDER]): reading every release to pick the maximum in
        // memory grew with each release for an answer that is one row.
        val latest = jdbi.withHandle<Row?, Exception> { handle ->
            handle.createQuery(
                """
                SELECT version, fingerprint
                FROM catalog_releases
                WHERE tenant_key = :t AND catalog_key = :c
                $LATEST_RELEASE_ORDER
                LIMIT 1
                """,
            )
                .bind("t", query.tenantKey)
                .bind("c", query.catalogKey)
                .map { rs, _ -> Row(version = rs.getString("version"), fingerprint = rs.getString("fingerprint")) }
                .findOne()
                .orElse(null)
        }

        // Bump from the current published version, or from 0.0.0 when never
        // released — so the three suggestions are always distinct and each is a
        // genuine +1 in its component (never released => 0.0.1 / 0.1.0 / 1.0.0).
        val base = latest?.let { SemVer.parseOrNull(it.version) } ?: SemVer(0, 0, 0)
        val suggested = SuggestedBumps(
            patch = base.bumpPatch().toString(),
            minor = base.bumpMinor().toString(),
            major = base.bumpMajor().toString(),
        )

        return LatestCatalogRelease(
            latestVersion = latest?.version,
            latestFingerprint = latest?.fingerprint,
            suggestedNext = suggested,
        )
    }
}
