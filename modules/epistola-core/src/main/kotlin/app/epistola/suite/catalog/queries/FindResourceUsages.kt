package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Finds cross-catalog usage counts for each resource in a catalog.
 * Returns a map of "type:slug" → list of usage descriptions.
 */
data class FindResourceUsages(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<Map<String, List<ResourceUsage>>>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
}

data class ResourceUsage(
    val referencedByName: String,
    val referencedByCatalog: String,
    val referenceType: String,
)

@Component
class FindResourceUsagesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<FindResourceUsages, Map<String, List<ResourceUsage>>> {

    override fun handle(query: FindResourceUsages): Map<String, List<ResourceUsage>> = jdbi.withHandle<Map<String, List<ResourceUsage>>, Exception> { handle ->
        val usages = mutableListOf<Pair<String, ResourceUsage>>()

        // Themes referenced by templates in other catalogs
        handle.createQuery(
            """
                SELECT dt.theme_key AS resource_slug, dt.name AS ref_name, dt.catalog_key AS ref_catalog
                FROM document_templates dt
                WHERE dt.tenant_key = :tenantKey
                  AND dt.theme_catalog_key = :catalogKey
                  AND dt.catalog_key != :catalogKey
                  AND dt.theme_key IS NOT NULL
                """,
        )
            .bind("tenantKey", query.tenantKey)
            .bind("catalogKey", query.catalogKey)
            .map { rs, _ ->
                "theme:${rs.getString("resource_slug")}" to ResourceUsage(
                    referencedByName = rs.getString("ref_name"),
                    referencedByCatalog = rs.getString("ref_catalog"),
                    referenceType = "template",
                )
            }
            .list()
            .let { usages.addAll(it) }

        // Stencils used by templates in other catalogs
        handle.createQuery(
            """
                SELECT DISTINCT
                    n.value -> 'props' ->> 'stencilId' AS resource_slug,
                    dt.name AS ref_name,
                    tv.catalog_key AS ref_catalog
                FROM template_versions tv
                JOIN document_templates dt ON dt.tenant_key = tv.tenant_key AND dt.catalog_key = tv.catalog_key AND dt.id = tv.template_key
                CROSS JOIN LATERAL jsonb_each(tv.template_model -> 'nodes') AS n(key, value)
                WHERE tv.tenant_key = :tenantKey
                  AND tv.catalog_key != :catalogKey
                  AND tv.status IN ('draft', 'published')
                  AND n.value ->> 'type' = 'stencil'
                  AND n.value -> 'props' ->> 'catalogKey' = :catalogKeyStr
                """,
        )
            .bind("tenantKey", query.tenantKey)
            .bind("catalogKey", query.catalogKey)
            .bind("catalogKeyStr", query.catalogKey.value)
            .map { rs, _ ->
                "stencil:${rs.getString("resource_slug")}" to ResourceUsage(
                    referencedByName = rs.getString("ref_name"),
                    referencedByCatalog = rs.getString("ref_catalog"),
                    referenceType = "template",
                )
            }
            .list()
            .let { usages.addAll(it) }

        // Assets used by templates in other catalogs
        handle.createQuery(
            """
                SELECT DISTINCT
                    a.id::text AS resource_slug,
                    dt.name AS ref_name,
                    tv.catalog_key AS ref_catalog
                FROM template_versions tv
                JOIN document_templates dt ON dt.tenant_key = tv.tenant_key AND dt.catalog_key = tv.catalog_key AND dt.id = tv.template_key
                CROSS JOIN LATERAL jsonb_each(tv.template_model -> 'nodes') AS n(key, value)
                JOIN assets a ON a.tenant_key = tv.tenant_key AND a.catalog_key = :catalogKey AND a.id::text = n.value -> 'props' ->> 'assetId'
                WHERE tv.tenant_key = :tenantKey
                  AND tv.catalog_key != :catalogKey
                  AND tv.status IN ('draft', 'published')
                  AND n.value ->> 'type' = 'image'
                """,
        )
            .bind("tenantKey", query.tenantKey)
            .bind("catalogKey", query.catalogKey)
            .map { rs, _ ->
                "asset:${rs.getString("resource_slug")}" to ResourceUsage(
                    referencedByName = rs.getString("ref_name"),
                    referencedByCatalog = rs.getString("ref_catalog"),
                    referenceType = "template",
                )
            }
            .list()
            .let { usages.addAll(it) }

        usages.groupBy({ it.first }, { it.second })
    }
}
