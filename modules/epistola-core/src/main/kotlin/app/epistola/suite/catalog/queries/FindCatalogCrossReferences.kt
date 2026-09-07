// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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
 * Finds cross-catalog references that depend on a given catalog.
 * Returns human-readable descriptions of each reference (e.g. "Template 'X' (catalog: Y) uses a theme").
 */
data class FindCatalogCrossReferences(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<List<String>>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class FindCatalogCrossReferencesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<FindCatalogCrossReferences, List<String>> {

    override fun handle(query: FindCatalogCrossReferences): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        val references = mutableListOf<String>()

        // 1. Templates in other catalogs that reference themes from this catalog
        handle.createQuery(
            """
            SELECT DISTINCT dt.name, dt.catalog_key
            FROM document_templates dt
            JOIN themes th ON th.tenant_key = dt.tenant_key AND th.resource_id = dt.theme_resource_id
            WHERE dt.tenant_key = :tenantKey
              AND th.catalog_key = :catalogKey
              AND dt.catalog_key != :catalogKey
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .bind("catalogKey", query.catalogKey)
            .map { rs, _ ->
                "Template '${rs.getString("name")}' (catalog: ${rs.getString("catalog_key")}) uses a theme"
            }
            .list()
            .let { references.addAll(it) }

        // 2. Templates in other catalogs that use stencils from this catalog
        handle.createQuery(
            """
            SELECT DISTINCT dt.name, dt.catalog_key
            FROM template_versions tv
            JOIN document_templates dt ON dt.tenant_key = tv.tenant_key AND dt.resource_id = tv.template_resource_id
            CROSS JOIN LATERAL jsonb_each(tv.template_model -> 'nodes') AS n(key, value)
            WHERE tv.tenant_key = :tenantKey
              AND dt.catalog_key != :catalogKey
              AND tv.status IN ('draft', 'published')
              AND n.value ->> 'type' = 'stencil'
              AND n.value -> 'props' ->> 'catalogKey' = :catalogKeyStr
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .bind("catalogKey", query.catalogKey)
            .bind("catalogKeyStr", query.catalogKey.value)
            .map { rs, _ ->
                "Template '${rs.getString("name")}' (catalog: ${rs.getString("catalog_key")}) uses a stencil"
            }
            .list()
            .let { references.addAll(it) }

        // 3. Templates in other catalogs that use assets from this catalog
        handle.createQuery(
            """
            SELECT DISTINCT dt.name, dt.catalog_key
            FROM template_versions tv
            JOIN document_templates dt ON dt.tenant_key = tv.tenant_key AND dt.resource_id = tv.template_resource_id
            CROSS JOIN LATERAL jsonb_each(tv.template_model -> 'nodes') AS n(key, value)
            JOIN assets a ON a.tenant_key = tv.tenant_key AND a.catalog_key = :catalogKey AND a.id::text = n.value -> 'props' ->> 'assetId'
            WHERE tv.tenant_key = :tenantKey
              AND dt.catalog_key != :catalogKey
              AND tv.status IN ('draft', 'published')
              AND n.value ->> 'type' = 'image'
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .bind("catalogKey", query.catalogKey)
            .map { rs, _ ->
                "Template '${rs.getString("name")}' (catalog: ${rs.getString("catalog_key")}) uses an asset"
            }
            .list()
            .let { references.addAll(it) }

        references
    }
}
