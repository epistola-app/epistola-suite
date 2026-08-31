// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

data class RelocatableResource(
    val address: ResourceAddress,
    val name: String,
    val catalogName: String,
    val catalogType: String,
)

/**
 * The resources a relocation batch can be built from.
 *
 * Only types registered in [MovableResource] are listed: offering anything else would produce a
 * preview that cannot execute. Resources in non-authored catalogs are listed too — they cannot be
 * moved, but omitting them would make an author think the resource had vanished rather than
 * understand why it is unavailable.
 */
data class ListRelocatableResources(
    override val tenantKey: TenantKey,
    val search: String? = null,
    val limit: Int = 50,
) : Query<List<RelocatableResource>>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class ListRelocatableResourcesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListRelocatableResources, List<RelocatableResource>> {
    override fun handle(query: ListRelocatableResources): List<RelocatableResource> = jdbi.withHandle<List<RelocatableResource>, Exception> { handle ->
        val types = MovableResource.entries.map { it.type.wireName }
        handle.createQuery(
            """
            SELECT resource_type, catalog_key, resource_key, resource_name, catalog_name, catalog_type
            FROM (
                SELECT 'stencil' resource_type, s.catalog_key::text, s.id::text resource_key, s.name resource_name,
                       c.name catalog_name, c.type::text catalog_type
                FROM stencils s JOIN catalogs c ON c.tenant_key = s.tenant_key AND c.id = s.catalog_key
                WHERE s.tenant_key = :tenantKey
                UNION ALL
                SELECT 'attribute', a.catalog_key::text, a.id::text, a.display_name, c.name, c.type::text
                FROM variant_attribute_definitions a JOIN catalogs c ON c.tenant_key = a.tenant_key AND c.id = a.catalog_key
                WHERE a.tenant_key = :tenantKey
                UNION ALL
                SELECT 'template', t.catalog_key::text, t.id::text, t.name, c.name, c.type::text
                FROM document_templates t JOIN catalogs c ON c.tenant_key = t.tenant_key AND c.id = t.catalog_key
                WHERE t.tenant_key = :tenantKey
            ) resources
            WHERE resource_type IN (<types>)
              AND (:search IS NULL OR resource_name ILIKE :search OR resource_key ILIKE :search)
            ORDER BY catalog_name, resource_type, resource_name
            LIMIT :limit
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .bindList("types", types)
            .bind("search", query.search?.takeIf { it.isNotBlank() }?.let { "%$it%" })
            .bind("limit", query.limit.coerceIn(1, 200))
            .map { rs, _ ->
                RelocatableResource(
                    address = ResourceAddress(
                        CatalogResourceType.entries.single { it.wireName == rs.getString("resource_type") },
                        rs.getString("catalog_key"),
                        rs.getString("resource_key"),
                    ),
                    name = rs.getString("resource_name"),
                    catalogName = rs.getString("catalog_name"),
                    catalogType = rs.getString("catalog_type"),
                )
            }
            .list()
    }
}
