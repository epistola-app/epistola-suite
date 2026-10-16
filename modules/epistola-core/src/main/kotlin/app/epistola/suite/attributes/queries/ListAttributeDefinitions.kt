// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.queries

import app.epistola.suite.attributes.model.VariantAttributeDefinition
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class ListAttributeDefinitions(
    val tenantId: TenantId,
    val catalogKey: CatalogKey? = null,
) : Query<List<VariantAttributeDefinition>>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = tenantId.key
}

@Component
class ListAttributeDefinitionsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListAttributeDefinitions, List<VariantAttributeDefinition>> {
    override fun handle(query: ListAttributeDefinitions): List<VariantAttributeDefinition> = jdbi.withHandle<List<VariantAttributeDefinition>, Exception> { handle ->
        val sql = buildString {
            append(
                """
                SELECT a.id, a.tenant_key, c.catalog_key AS catalog_key, c.type AS catalog_type, a.display_name, a.allowed_values,
                       cl_cat.catalog_key AS code_list_catalog_key, cl.slug AS code_list_slug,
                       a.created_at, a.updated_at
                FROM variant_attribute_definitions a
                JOIN catalogs c ON c.tenant_key = a.tenant_key AND c.catalog_id = a.catalog_id
                LEFT JOIN code_lists cl ON cl.tenant_key = a.tenant_key AND cl.resource_id = a.code_list_resource_id
                LEFT JOIN catalogs cl_cat ON cl_cat.tenant_key = cl.tenant_key AND cl_cat.catalog_id = cl.catalog_id
                WHERE a.tenant_key = :tenantId
                """,
            )
            if (query.catalogKey != null) {
                append(" AND a.catalog_id = ${catalogAt("tenantId", "catalogKey")}")
            }
            append(" ORDER BY a.display_name ASC")
        }

        val jdbiQuery = handle.createQuery(sql)
            .bind("tenantId", query.tenantId.key)
        if (query.catalogKey != null) {
            jdbiQuery.bind("catalogKey", query.catalogKey)
        }
        jdbiQuery
            .mapTo<VariantAttributeDefinition>()
            .list()
    }
}
