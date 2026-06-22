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
import java.time.OffsetDateTime

/**
 * Cheap read of an AUTHORED catalog's release pointer + history straight from
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

data class ReleaseSummary(
    val version: String,
    val releasedAt: OffsetDateTime,
    val notes: String?,
)

data class LatestCatalogRelease(
    val latestVersion: String?,
    val latestFingerprint: String?,
    val suggestedNext: SuggestedBumps,
    val history: List<ReleaseSummary>,
)

@Component
class GetLatestCatalogReleaseHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetLatestCatalogRelease, LatestCatalogRelease> {

    private data class Row(val version: String, val fingerprint: String, val releasedAt: OffsetDateTime, val notes: String?)

    override fun handle(query: GetLatestCatalogRelease): LatestCatalogRelease {
        val rows = jdbi.withHandle<List<Row>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT version, fingerprint, released_at, notes
                FROM catalog_releases
                WHERE tenant_key = :t AND catalog_key = :c
                ORDER BY released_at DESC
                """,
            )
                .bind("t", query.tenantKey)
                .bind("c", query.catalogKey)
                .map { rs, _ ->
                    Row(
                        version = rs.getString("version"),
                        fingerprint = rs.getString("fingerprint"),
                        releasedAt = rs.getObject("released_at", OffsetDateTime::class.java),
                        notes = rs.getString("notes"),
                    )
                }
                .list()
        }

        // "Latest" = highest SemVer (versions are enforced strictly increasing).
        val latest = rows.maxByOrNull { SemVer.parseOrNull(it.version) ?: SemVer(0, 0, 0) }

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
            history = rows.map { ReleaseSummary(it.version, it.releasedAt, it.notes) },
        )
    }
}
