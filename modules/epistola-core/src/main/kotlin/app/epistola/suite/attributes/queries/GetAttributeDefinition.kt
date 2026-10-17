// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.queries

import app.epistola.suite.attributes.model.VariantAttributeDefinition
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionScoped
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetAttributeDefinition(
    val id: AttributeId,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogVersionRef? = null,
) : CatalogVersionScoped,
    Query<VariantAttributeDefinition?>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = id.tenantKey
}

@Component
class GetAttributeDefinitionHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetAttributeDefinition, VariantAttributeDefinition?> {
    override fun handle(query: GetAttributeDefinition): VariantAttributeDefinition? = jdbi.withHandle<VariantAttributeDefinition?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT a.id, a.tenant_key, c.catalog_key AS catalog_key, c.type AS catalog_type,
                       a.display_name, a.allowed_values,
                       cl_cat.catalog_key AS code_list_catalog_key, cl.slug AS code_list_slug,
                       a.created_at, a.updated_at
                FROM variant_attribute_definitions a
                JOIN catalogs c ON c.tenant_key = a.tenant_key AND c.catalog_id = a.catalog_id
                LEFT JOIN code_lists cl ON cl.tenant_key = a.tenant_key AND cl.resource_id = a.code_list_resource_id AND ${inCurrentVersion("cl")}
                LEFT JOIN catalogs cl_cat ON cl_cat.tenant_key = cl.tenant_key AND cl_cat.catalog_id = cl.catalog_id
                WHERE a.id = :id AND a.tenant_key = :tenantId AND a.version_id = ${versionAt("tenantId", "catalogKey")}
                """,
        )
            .bind("id", query.id.key)
            .bind("tenantId", query.id.tenantKey)
            .bind("catalogKey", query.id.catalogKey)
            .mapTo<VariantAttributeDefinition>()
            .findOne()
            .orElse(null)
    }
}
