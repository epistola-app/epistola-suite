package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogClient
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Cheap "is an upgrade available?" check for a SUBSCRIBED catalog — fetches
 * **only the manifest** (no per-resource details), so it is safe to lazy-load
 * once per subscribed row on the catalog list. The full ADDED/REMOVED/CHANGED
 * breakdown is [PreviewCatalogUpgrade] (heavier; loaded on "Review changes").
 *
 * Uses the same change gate as
 * [EnsureSubscribedCatalog][app.epistola.suite.catalog.commands.EnsureSubscribedCatalog]:
 * the manifest fingerprint vs the installed fingerprint, falling back to the
 * version string when the manifest carries no `release.fingerprint`.
 */
data class CheckCatalogUpgrade(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<CatalogUpgradeAvailability>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_SETTINGS
}

data class CatalogUpgradeAvailability(
    val available: Boolean,
    val installedVersion: String?,
    val availableVersion: String,
)

@Component
class CheckCatalogUpgradeHandler(
    private val catalogClient: CatalogClient,
) : QueryHandler<CheckCatalogUpgrade, CatalogUpgradeAvailability> {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(query: CheckCatalogUpgrade): CatalogUpgradeAvailability {
        val catalog = GetCatalog(query.tenantKey, query.catalogKey).query()
            ?: throw IllegalArgumentException("Catalog not found: ${query.catalogKey}")

        val sourceUrl = catalog.sourceUrl
            ?: throw IllegalStateException("Catalog '${query.catalogKey}' has no source URL — only subscribed catalogs can be upgraded")

        val manifest = catalogClient.fetchManifest(sourceUrl, catalog.sourceAuthType, catalog.sourceAuthCredential)
        val manifestFingerprint = manifest.release.fingerprint

        val available = if (manifestFingerprint != null) {
            catalog.installedFingerprint != manifestFingerprint
        } else {
            log.warn(
                "Catalog '{}' manifest has no release.fingerprint — using version-string change detection for upgrade availability (tenant {})",
                query.catalogKey.value,
                query.tenantKey.value,
            )
            catalog.installedReleaseVersion != manifest.release.version
        }

        return CatalogUpgradeAvailability(
            available = available,
            installedVersion = catalog.installedReleaseVersion,
            availableVersion = manifest.release.version,
        )
    }
}
