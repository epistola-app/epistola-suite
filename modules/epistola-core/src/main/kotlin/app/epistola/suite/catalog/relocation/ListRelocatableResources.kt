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
    /**
     * Why moving this will have a consequence worth knowing about, or null when it is unremarkable.
     * Not a reason it cannot move -- anything listed here can.
     */
    val note: String? = null,
)

/**
 * The resources a relocation batch can be built from.
 *
 * Only types registered in [MovableResource] are listed: offering anything else would produce a
 * preview that cannot execute. Non-authored catalogs are excluded for the same reason — a
 * subscribed or system catalog is not the tenant's to rearrange, so listing its resources would
 * only offer choices that cannot be taken.
 *
 * A resource whose catalog has been released is still listed, carrying a [RelocatableResource.note]:
 * it can move, but subscribers will not follow it. That is a judgement for the operator rather than
 * a reason to hide the resource.
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
            WITH authored AS (
                SELECT c.id, c.name,
                       EXISTS(
                           SELECT 1 FROM catalog_releases r
                           WHERE r.tenant_key = c.tenant_key AND r.catalog_key = c.id
                       ) released
                FROM catalogs c
                WHERE c.tenant_key = :tenantKey AND c.type = 'AUTHORED'
            )
            SELECT resource_type, catalog_key, resource_key, resource_name, catalog_name, released
            FROM (
                SELECT 'stencil' resource_type, s.catalog_key::text, s.id::text resource_key, s.name resource_name,
                       c.name catalog_name, c.released
                FROM stencils s JOIN authored c ON c.id = s.catalog_key
                WHERE s.tenant_key = :tenantKey
                UNION ALL
                SELECT 'attribute', a.catalog_key::text, a.id::text, a.display_name, c.name, c.released
                FROM variant_attribute_definitions a JOIN authored c ON c.id = a.catalog_key
                WHERE a.tenant_key = :tenantKey
                UNION ALL
                SELECT 'template', t.catalog_key::text, t.id::text, t.name, c.name, c.released
                FROM document_templates t JOIN authored c ON c.id = t.catalog_key
                WHERE t.tenant_key = :tenantKey
                UNION ALL
                SELECT 'codeList', l.catalog_key::text, l.slug::text, l.display_name, c.name, c.released
                FROM code_lists l JOIN authored c ON c.id = l.catalog_key
                WHERE l.tenant_key = :tenantKey
                UNION ALL
                SELECT 'asset', a.catalog_key::text, a.id::text, a.name, c.name, c.released
                FROM assets a JOIN authored c ON c.id = a.catalog_key
                WHERE a.tenant_key = :tenantKey
                UNION ALL
                SELECT 'font', f.catalog_key::text, f.slug::text, f.name, c.name, c.released
                FROM fonts f JOIN authored c ON c.id = f.catalog_key
                WHERE f.tenant_key = :tenantKey
                UNION ALL
                SELECT 'theme', th.catalog_key::text, th.id::text, th.name, c.name, c.released
                FROM themes th JOIN authored c ON c.id = th.catalog_key
                WHERE th.tenant_key = :tenantKey
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
                    note = "Released — subscribers will not follow this move".takeIf { rs.getBoolean("released") },
                )
            }
            .list()
    }
}
