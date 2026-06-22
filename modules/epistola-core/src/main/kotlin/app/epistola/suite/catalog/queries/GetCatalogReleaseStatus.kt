package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.springframework.stereotype.Component

/**
 * Drift-aware release status of an AUTHORED catalog: the latest release (via
 * the cheap [GetLatestCatalogRelease]) **plus** whether the live working copy
 * has unreleased changes (an O(catalog-size) content fingerprint recompute).
 *
 * Callers that only need the released pointer (e.g. `ExportCatalogZip`) should
 * use [GetLatestCatalogRelease] directly and avoid the working-copy build.
 */
data class GetCatalogReleaseStatus(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<CatalogReleaseStatus>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

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
    private val fingerprintService: CatalogFingerprintService,
) : QueryHandler<GetCatalogReleaseStatus, CatalogReleaseStatus> {

    override fun handle(query: GetCatalogReleaseStatus): CatalogReleaseStatus {
        val release = GetLatestCatalogRelease(query.tenantKey, query.catalogKey).query()
        val workingFingerprint = fingerprintService.fingerprint(query.tenantKey, query.catalogKey)

        return CatalogReleaseStatus(
            latestVersion = release.latestVersion,
            latestFingerprint = release.latestFingerprint,
            workingFingerprint = workingFingerprint,
            hasUnreleasedChanges = release.latestFingerprint == null || release.latestFingerprint != workingFingerprint,
            suggestedNext = release.suggestedNext,
            history = release.history,
        )
    }
}
