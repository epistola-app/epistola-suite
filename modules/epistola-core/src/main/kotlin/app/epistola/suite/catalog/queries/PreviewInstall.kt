package app.epistola.suite.catalog.queries

import app.epistola.catalog.protocol.ResourceEntry
import app.epistola.suite.catalog.CatalogClient
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.DependencyResolver
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.springframework.stereotype.Component

/**
 * Previews what would be installed from a catalog, including auto-resolved dependencies.
 * Used to show a confirmation dialog before actual installation.
 */
data class PreviewInstall(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val resourceSlugs: List<String>? = null,
) : Query<PreviewInstallResult>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
}

data class PreviewInstallResult(
    val selected: List<ResourceEntry>,
    val dependencies: List<ResourceEntry>,
    val all: List<ResourceEntry>,
)

@Component
class PreviewInstallHandler(
    private val catalogClient: CatalogClient,
    private val dependencyResolver: DependencyResolver,
) : QueryHandler<PreviewInstall, PreviewInstallResult> {

    override fun handle(query: PreviewInstall): PreviewInstallResult {
        val catalog = GetCatalog(query.tenantKey, query.catalogKey).query()
            ?: throw IllegalArgumentException("Catalog not found: ${query.catalogKey}")

        val sourceUrl = catalog.sourceUrl
            ?: throw IllegalStateException("Catalog has no source URL: ${query.catalogKey}")

        val manifest = catalogClient.fetchManifest(sourceUrl, catalog.sourceAuthType, catalog.sourceAuthCredential)

        val selected = if (query.resourceSlugs != null) {
            manifest.resources.filter { it.slug in query.resourceSlugs }
        } else {
            manifest.resources
        }

        val all = dependencyResolver.resolve(selected, manifest, sourceUrl, catalog.sourceAuthType, catalog.sourceAuthCredential)
        val selectedKeys = selected.map { "${it.type}:${it.slug}" }.toSet()
        val dependencies = all.filter { "${it.type}:${it.slug}" !in selectedKeys }

        return PreviewInstallResult(
            selected = selected,
            dependencies = dependencies,
            all = all,
        )
    }
}
