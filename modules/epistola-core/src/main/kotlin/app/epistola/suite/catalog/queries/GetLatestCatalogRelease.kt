// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Cheap read of a catalog's release pointer straight from
 * `catalog_releases` — **no catalog content build / fingerprint recompute**.
 *
 * Split out of [GetCatalogReleaseStatus] so callers that only need the latest
 * released version/fingerprint (notably [ExportCatalogZip][app.epistola.suite.catalog.commands.ExportCatalogZip])
 * don't pay an O(catalog-size) content build. The drift-aware
 * [GetCatalogReleaseStatus] delegates here and adds the working-copy compare.
 */
data class GetLatestCatalogRelease(
    val catalog: CatalogId,
) : Query<LatestCatalogRelease>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
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
 * Releases by number, highest first, over `catalog_releases` as [alias] (none for the table itself):
 * by SemVer precedence on the generated version components (V20261022110352), so a patch release
 * (`1.3.1-gemeente-x.1`) sorts above `1.3.0` and below `1.3.1`, patch labels compared as ASCII and
 * then by number. Labels that are not release numbers have null components — `SemVer.parseOrNull`
 * tolerates them — so they sort last; ties fall back to when they were released.
 */
internal fun releaseNumberOrder(alias: String = "", descending: Boolean = true): String {
    val p = if (alias.isEmpty()) "" else "$alias."
    return if (descending) {
        "${p}version_major DESC NULLS LAST, ${p}version_minor DESC NULLS LAST, ${p}version_patch DESC NULLS LAST, " +
            "(${p}version_label IS NULL) DESC, ${p}version_label COLLATE \"C\" DESC NULLS LAST, ${p}version_label_number DESC NULLS LAST, ${p}released_at DESC"
    } else {
        "${p}version_major NULLS FIRST, ${p}version_minor NULLS FIRST, ${p}version_patch NULLS FIRST, " +
            "(${p}version_label IS NULL), ${p}version_label COLLATE \"C\" NULLS FIRST, ${p}version_label_number NULLS FIRST, ${p}released_at"
    }
}

/** Newest release of a catalog first ([releaseNumberOrder]). */
internal val LATEST_RELEASE_ORDER = "ORDER BY ${releaseNumberOrder()}"

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
                WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}
                $LATEST_RELEASE_ORDER
                LIMIT 1
                """,
            )
                .bind("t", query.tenantKey)
                .bindCatalog("c", query.catalog)
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
