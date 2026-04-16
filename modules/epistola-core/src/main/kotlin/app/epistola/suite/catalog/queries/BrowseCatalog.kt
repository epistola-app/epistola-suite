package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogClient
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogType
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
                SELECT id, tenant_key, name, description, type, source_url, source_auth_type, source_auth_credential, installed_release_version, installed_at, created_at, last_modified
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

        return when (catalog.type) {
            CatalogType.AUTHORED -> browseAuthored(catalog, query)
            CatalogType.SUBSCRIBED -> browseSubscribed(catalog, query)
        }
    }

    /**
     * Browse an authored catalog — list resources directly from the database.
     * All resources are local, so status is always INSTALLED.
     */
    private fun browseAuthored(catalog: Catalog, query: BrowseCatalog): BrowseResult {
        val resources = jdbi.withHandle<List<BrowseResource>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT 'template' as type, id as slug, name, NULL as description FROM document_templates WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                UNION ALL
                SELECT 'theme', id, name, description FROM themes WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                UNION ALL
                SELECT 'stencil', id, name, description FROM stencils WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                UNION ALL
                SELECT 'attribute', id, display_name, NULL FROM variant_attribute_definitions WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                UNION ALL
                SELECT 'asset', id::text, name, NULL FROM assets WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                """,
            )
                .bind("tenantKey", query.tenantKey)
                .bind("catalogKey", query.catalogKey)
                .map { rs, _ ->
                    BrowseResource(
                        type = rs.getString("type"),
                        slug = rs.getString("slug"),
                        name = rs.getString("name"),
                        description = rs.getString("description"),
                        status = ResourceStatus.INSTALLED,
                    )
                }
                .list()
        }

        return BrowseResult(catalog = catalog, resources = resources)
    }

    /**
     * Browse a subscribed catalog — fetch the remote manifest and check which resources are installed.
     */
    private fun browseSubscribed(catalog: Catalog, query: BrowseCatalog): BrowseResult {
        val installedResources = jdbi.withHandle<Set<String>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT 'template:' || id FROM document_templates WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                UNION ALL
                SELECT 'theme:' || id FROM themes WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                UNION ALL
                SELECT 'stencil:' || id FROM stencils WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                UNION ALL
                SELECT 'attribute:' || id FROM variant_attribute_definitions WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                UNION ALL
                SELECT 'asset:' || id FROM assets WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                """,
            )
                .bind("tenantKey", query.tenantKey)
                .bind("catalogKey", query.catalogKey)
                .mapTo(String::class.java)
                .set()
        }

        val manifest = catalogClient.fetchManifest(
            catalog.sourceUrl ?: throw IllegalStateException("Subscribed catalog has no source URL"),
            catalog.sourceAuthType,
            catalog.sourceAuthCredential,
        )

        val resources = manifest.resources.map { entry ->
            val key = "${entry.type}:${entry.slug}"
            BrowseResource(
                type = entry.type,
                slug = entry.slug,
                name = entry.name,
                description = entry.description,
                status = if (key in installedResources) ResourceStatus.INSTALLED else ResourceStatus.AVAILABLE,
            )
        }

        return BrowseResult(catalog = catalog, resources = resources)
    }
}
