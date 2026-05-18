package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogFingerprintService
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
 * Release status of an AUTHORED catalog: the latest release, whether the live
 * working copy has unreleased changes (content fingerprint differs from the
 * latest release), suggested next versions and the release history.
 *
 * The shared primitive behind the UI release dialog, the export drift check
 * and the REST/MCP read surfaces.
 */
data class GetCatalogReleaseStatus(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<CatalogReleaseStatus>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
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

data class CatalogReleaseStatus(
    val latestVersion: String?,
    val latestFingerprint: String?,
    val workingFingerprint: String,
    val hasUnreleasedChanges: Boolean,
    val suggestedNext: SuggestedBumps,
    val history: List<ReleaseSummary>,
)

@Component
class GetCatalogReleaseStatusHandler(
    private val jdbi: Jdbi,
    private val fingerprintService: CatalogFingerprintService,
) : QueryHandler<GetCatalogReleaseStatus, CatalogReleaseStatus> {

    private data class Row(val version: String, val fingerprint: String, val releasedAt: OffsetDateTime, val notes: String?)

    override fun handle(query: GetCatalogReleaseStatus): CatalogReleaseStatus {
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
        val latestSemVer = latest?.let { SemVer.parseOrNull(it.version) }

        val workingFingerprint = fingerprintService.fingerprint(query.tenantKey, query.catalogKey)

        val suggested = if (latestSemVer == null) {
            SuggestedBumps("1.0.0", "1.0.0", "1.0.0")
        } else {
            SuggestedBumps(
                patch = latestSemVer.bumpPatch().toString(),
                minor = latestSemVer.bumpMinor().toString(),
                major = latestSemVer.bumpMajor().toString(),
            )
        }

        return CatalogReleaseStatus(
            latestVersion = latest?.version,
            latestFingerprint = latest?.fingerprint,
            workingFingerprint = workingFingerprint,
            hasUnreleasedChanges = latest == null || latest.fingerprint != workingFingerprint,
            suggestedNext = suggested,
            history = rows.map { ReleaseSummary(it.version, it.releasedAt, it.notes) },
        )
    }
}
