// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionScoped
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
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogVersionRef? = null,
) : CatalogVersionScoped,
    Query<Map<String, List<ResourceUsage>>>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
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
                SELECT th.id AS resource_slug, dt.name AS ref_name, dc.catalog_key AS ref_catalog
                FROM document_templates dt
                JOIN catalogs dc ON dc.tenant_key = dt.tenant_key AND dc.catalog_id = dt.catalog_id
                JOIN themes th ON th.tenant_key = dt.tenant_key AND th.resource_id = dt.theme_resource_id
                WHERE dt.tenant_key = :tenantKey
                  AND th.version_id = ${versionAt("tenantKey", "catalogKey")}
                  AND dt.catalog_id != ${catalogAt("tenantKey", "catalogKey")}
                  AND ${inCurrentVersion("dt")}
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
                    n.value -> 'props' ->> 'stencilKey' AS resource_slug,
                    dt.name AS ref_name,
                    dc.catalog_key AS ref_catalog
                FROM template_variants tv
                JOIN document_templates dt ON dt.tenant_key = tv.tenant_key AND dt.version_id = tv.version_id AND dt.resource_id = tv.template_resource_id
                JOIN catalogs dc ON dc.tenant_key = dt.tenant_key AND dc.catalog_id = dt.catalog_id
                CROSS JOIN LATERAL jsonb_each(tv.template_model -> 'nodes') AS n(key, value)
                WHERE tv.tenant_key = :tenantKey
                  AND dt.catalog_id != ${catalogAt("tenantKey", "catalogKey")}
                  AND ${inCurrentVersion("dt")}
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
                    dc.catalog_key AS ref_catalog
                FROM template_variants tv
                JOIN document_templates dt ON dt.tenant_key = tv.tenant_key AND dt.version_id = tv.version_id AND dt.resource_id = tv.template_resource_id
                JOIN catalogs dc ON dc.tenant_key = dt.tenant_key AND dc.catalog_id = dt.catalog_id
                CROSS JOIN LATERAL jsonb_each(tv.template_model -> 'nodes') AS n(key, value)
                JOIN assets a ON a.tenant_key = tv.tenant_key AND a.version_id = ${versionAt("tenantKey", "catalogKey")} AND a.id::text = n.value -> 'props' ->> 'imageKey'
                WHERE tv.tenant_key = :tenantKey
                  AND dt.catalog_id != ${catalogAt("tenantKey", "catalogKey")}
                  AND ${inCurrentVersion("dt")}
                  AND n.value ->> 'type' = 'image'
                """,
        )
            .bind("tenantKey", query.tenantKey)
            .bind("catalogKey", query.catalogKey)
            .map { rs, _ ->
                "image:${rs.getString("resource_slug")}" to ResourceUsage(
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
