// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogClient
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.config.findByTenantAndId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

data class BrowseCatalog(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<BrowseResult>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
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

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(query: BrowseCatalog): BrowseResult {
        val catalog = jdbi.withHandle<Catalog, Exception> { handle ->
            handle.findByTenantAndId<Catalog>("catalogs", query.tenantKey, query.catalogKey.value)
                ?: throw IllegalArgumentException("Catalog not found: ${query.catalogKey}")
        }

        return when (catalog.type) {
            CatalogType.AUTHORED -> browseAuthored(catalog, query)
            CatalogType.SUBSCRIBED -> browseSubscribed(catalog, query)
        }
    }

    /**
     * The locally-installed resources of `(tenant, catalog)`, all marked
     * INSTALLED. The ground truth for an AUTHORED catalog, and the safe fallback
     * for a SUBSCRIBED one with no reachable source (ZIP-managed, or remote
     * temporarily unavailable) — browsing must never hard-fail.
     */
    private fun installedResources(query: BrowseCatalog): List<BrowseResource> = jdbi.withHandle<List<BrowseResource>, Exception> { handle ->
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
            UNION ALL
            SELECT 'codeList', slug::text, display_name, description FROM code_lists WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
            UNION ALL
            SELECT 'font', slug::text, name, NULL FROM fonts WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
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

    /** AUTHORED — all resources are local; status is always INSTALLED. */
    private fun browseAuthored(catalog: Catalog, query: BrowseCatalog): BrowseResult = BrowseResult(catalog = catalog, resources = installedResources(query))

    /**
     * SUBSCRIBED — when a source URL is reachable, list the manifest's
     * resources and mark which are installed (so not-yet-installed ones show as
     * AVAILABLE). When there is **no source URL** (a ZIP-managed mirror) or the
     * source is unreachable, degrade gracefully to the locally-installed
     * resources — browsing a catalog must never error the whole page.
     */
    private fun browseSubscribed(catalog: Catalog, query: BrowseCatalog): BrowseResult {
        val sourceUrl = catalog.sourceUrl
            ?: return BrowseResult(catalog = catalog, resources = installedResources(query))

        val manifest = try {
            catalogClient.fetchManifest(sourceUrl, catalog.sourceAuthType, catalog.sourceAuthCredential?.value)
        } catch (e: Exception) {
            logger.warn(
                "Browsing subscribed catalog '{}' — source {} unreachable ({}); showing installed resources only",
                query.catalogKey.value,
                sourceUrl,
                e.message,
            )
            return BrowseResult(catalog = catalog, resources = installedResources(query))
        }

        val installedKeys = jdbi.withHandle<Set<String>, Exception> { handle ->
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
                UNION ALL
                SELECT 'codeList:' || slug FROM code_lists WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                UNION ALL
                SELECT 'font:' || slug FROM fonts WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                """,
            )
                .bind("tenantKey", query.tenantKey)
                .bind("catalogKey", query.catalogKey)
                .mapTo(String::class.java)
                .set()
        }

        val resources = manifest.resources.map { entry ->
            BrowseResource(
                type = entry.type,
                slug = entry.slug,
                name = entry.name,
                description = entry.description,
                status = if ("${entry.type}:${entry.slug}" in installedKeys) ResourceStatus.INSTALLED else ResourceStatus.AVAILABLE,
            )
        }

        return BrowseResult(catalog = catalog, resources = resources)
    }
}
