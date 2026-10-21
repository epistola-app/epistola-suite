// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogClient
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.findCatalog
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionScoped
import app.epistola.suite.common.ids.TenantKey
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
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogVersionRef? = null,
) : CatalogVersionScoped,
    Query<BrowseResult>,
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
            handle.findCatalog(query.tenantKey, query.catalogKey)
                ?: throw IllegalArgumentException("Catalog not found: ${query.catalogKey}")
        }

        return browse(catalog, query)
    }

    /**
     * The locally-installed resources of `(tenant, catalog)`, all marked
     * INSTALLED. The ground truth for a catalog without a source, and the safe
     * fallback for one whose source cannot be reached (or is not a manifest URL)
     * — browsing must never hard-fail.
     */
    private fun installedResources(query: BrowseCatalog): List<BrowseResource> = jdbi.withHandle<List<BrowseResource>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT 'template' as type, id as slug, name, NULL as description FROM document_templates WHERE tenant_key = :tenantKey AND version_id = ${versionAt("tenantKey", "catalogKey")}
            UNION ALL
            SELECT 'theme', id, name, description FROM themes WHERE tenant_key = :tenantKey AND version_id = ${versionAt("tenantKey", "catalogKey")}
            UNION ALL
            SELECT 'stencil', id, name, description FROM stencils WHERE tenant_key = :tenantKey AND version_id = ${versionAt("tenantKey", "catalogKey")}
            UNION ALL
            SELECT 'attribute', id, display_name, NULL FROM variant_attribute_definitions WHERE tenant_key = :tenantKey AND version_id = ${versionAt("tenantKey", "catalogKey")}
            UNION ALL
            SELECT 'image', id::text, name, NULL FROM assets WHERE tenant_key = :tenantKey AND version_id = ${versionAt("tenantKey", "catalogKey")}
            UNION ALL
            SELECT 'codeList', slug::text, display_name, description FROM code_lists WHERE tenant_key = :tenantKey AND version_id = ${versionAt("tenantKey", "catalogKey")}
            UNION ALL
            SELECT 'font', slug::text, name, NULL FROM fonts WHERE tenant_key = :tenantKey AND version_id = ${versionAt("tenantKey", "catalogKey")}
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

    /**
     * When the catalog has a source that is reachable, list the manifest's
     * resources and mark which are installed (so not-yet-installed ones show as
     * AVAILABLE). When there is **no source** (made here, or imported from a file)
     * or the source is unreachable, the local resources, all INSTALLED — browsing
     * a catalog must never error the whole page.
     */
    private fun browse(catalog: Catalog, query: BrowseCatalog): BrowseResult {
        val sourceUrl = catalog.sourceUrl
            ?: return BrowseResult(catalog = catalog, resources = installedResources(query))

        val manifest = try {
            catalogClient.fetchManifest(sourceUrl, catalog.sourceAuthType, catalog.sourceAuthCredential?.value)
        } catch (e: Exception) {
            logger.warn(
                "Browsing catalog '{}' — source {} unreachable ({}); showing installed resources only",
                query.catalogKey.value,
                sourceUrl,
                e.message,
            )
            return BrowseResult(catalog = catalog, resources = installedResources(query))
        }

        val installedKeys = jdbi.withHandle<Set<String>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT 'template:' || id FROM document_templates WHERE tenant_key = :tenantKey AND version_id = ${versionAt("tenantKey", "catalogKey")}
                UNION ALL
                SELECT 'theme:' || id FROM themes WHERE tenant_key = :tenantKey AND version_id = ${versionAt("tenantKey", "catalogKey")}
                UNION ALL
                SELECT 'stencil:' || id FROM stencils WHERE tenant_key = :tenantKey AND version_id = ${versionAt("tenantKey", "catalogKey")}
                UNION ALL
                SELECT 'attribute:' || id FROM variant_attribute_definitions WHERE tenant_key = :tenantKey AND version_id = ${versionAt("tenantKey", "catalogKey")}
                UNION ALL
                SELECT 'image:' || id FROM assets WHERE tenant_key = :tenantKey AND version_id = ${versionAt("tenantKey", "catalogKey")}
                UNION ALL
                SELECT 'codeList:' || slug FROM code_lists WHERE tenant_key = :tenantKey AND version_id = ${versionAt("tenantKey", "catalogKey")}
                UNION ALL
                SELECT 'font:' || slug FROM fonts WHERE tenant_key = :tenantKey AND version_id = ${versionAt("tenantKey", "catalogKey")}
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
                slug = entry.key,
                name = entry.name,
                description = entry.description,
                status = if ("${entry.type}:${entry.key}" in installedKeys) ResourceStatus.INSTALLED else ResourceStatus.AVAILABLE,
            )
        }

        return BrowseResult(catalog = catalog, resources = resources)
    }
}
