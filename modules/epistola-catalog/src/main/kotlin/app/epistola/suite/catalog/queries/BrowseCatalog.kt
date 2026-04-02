package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogClient
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class BrowseCatalog(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<BrowseResult>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
}

data class BrowseResult(
    val catalog: Catalog,
    val resources: List<BrowseResource>,
)

data class BrowseResource(
    val type: String,
    val slug: String,
    val name: String,
    val description: String?,
    val status: ResourceStatus,
)

enum class ResourceStatus {
    AVAILABLE,
    INSTALLED,
}

@Component
class BrowseCatalogHandler(
    private val jdbi: Jdbi,
    private val catalogClient: CatalogClient,
) : QueryHandler<BrowseCatalog, BrowseResult> {

    override fun handle(query: BrowseCatalog): BrowseResult {
        val catalog = jdbi.withHandle<Catalog, Exception> { handle ->
            handle.createQuery(
                """
                SELECT id, tenant_key, name, description, type, source_url, source_auth_type, installed_release_version, installed_at, created_at, last_modified
                FROM catalogs
                WHERE tenant_key = :tenantKey AND id = :catalogKey
                """,
            )
                .bind("tenantKey", query.tenantKey)
                .bind("catalogKey", query.catalogKey)
                .mapTo<Catalog>()
                .findOne()
                .orElseThrow { IllegalArgumentException("Catalog not found: ${query.catalogKey}") }
        }

        val installedSlugs = jdbi.withHandle<Set<String>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT catalog_resource_slug
                FROM catalog_templates
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                """,
            )
                .bind("tenantKey", query.tenantKey)
                .bind("catalogKey", query.catalogKey)
                .mapTo(String::class.java)
                .set()
        }

        val manifest = catalogClient.fetchManifest(
            catalog.sourceUrl ?: throw IllegalStateException("Catalog has no source URL"),
            catalog.sourceAuthType,
            null,
        )

        val resources = manifest.resources.map { entry ->
            BrowseResource(
                type = entry.type,
                slug = entry.slug,
                name = entry.name,
                description = entry.description,
                status = if (entry.slug in installedSlugs) ResourceStatus.INSTALLED else ResourceStatus.AVAILABLE,
            )
        }

        return BrowseResult(catalog = catalog, resources = resources)
    }
}
