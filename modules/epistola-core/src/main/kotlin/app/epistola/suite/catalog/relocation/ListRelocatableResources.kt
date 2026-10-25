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
 * preview that cannot execute. Catalogs that cannot be changed here are excluded for the same
 * reason — one Epistola supplies, or any on a run-only installation (ADR 0032) — so listing their
 * resources would only offer choices that cannot be taken.
 *
 * A resource whose catalog has been released is still listed, carrying a [RelocatableResource.note]:
 * it can move, but subscribers will not follow it. That is a judgement for the operator rather than
 * a reason to hide the resource.
 */
data class ListRelocatableResources(
    override val tenantKey: TenantKey,
    val search: String? = null,
    val limit: Int = 50,
    /** Restricts the listing to these resources, e.g. the ones a deep link names; empty lists all. */
    val addresses: Set<ResourceAddress> = emptySet(),
) : Query<List<RelocatableResource>>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class ListRelocatableResourcesHandler(
    private val jdbi: Jdbi,
    private val editing: app.epistola.suite.catalog.CatalogEditingProperties,
) : QueryHandler<ListRelocatableResources, List<RelocatableResource>> {
    override fun handle(query: ListRelocatableResources): List<RelocatableResource> = if (!editing.enabled) {
        // A run-only installation changes no catalog, so nothing can move (ADR 0032).
        emptyList()
    } else {
        list(query)
    }

    private fun list(query: ListRelocatableResources): List<RelocatableResource> = jdbi.withHandle<List<RelocatableResource>, Exception> { handle ->
        val types = MovableResource.entries.map { it.type.wireName }
        handle.createQuery(
            """
            -- Each catalog that can be changed here (not one Epistola supplies, ADR 0032) with its
            -- current version, whose content is what can move.
            WITH editable AS (
                SELECT c.catalog_id, cv.version_id, c.namespace, c.catalog_key, c.name,
                       EXISTS(
                           SELECT 1 FROM catalog_releases r
                           WHERE r.tenant_key = c.tenant_key AND r.catalog_id = c.catalog_id
                       ) released
                FROM catalogs c
                JOIN catalog_current_versions cv ON cv.tenant_key = c.tenant_key AND cv.catalog_id = c.catalog_id
                WHERE c.tenant_key = :tenantKey AND COALESCE(c.source_url NOT LIKE 'classpath:%', TRUE)
            )
            SELECT resource_type, namespace, catalog_key, resource_key, resource_name, catalog_name, released
            FROM (
                SELECT 'stencil' resource_type, c.namespace, c.catalog_key::text, s.id::text resource_key, s.name resource_name,
                       c.name catalog_name, c.released
                FROM stencils s JOIN editable c ON c.version_id = s.version_id
                WHERE s.tenant_key = :tenantKey
                UNION ALL
                SELECT 'attribute', c.namespace, c.catalog_key::text, a.id::text, a.display_name, c.name, c.released
                FROM current_attributes a JOIN editable c ON c.version_id = a.version_id
                WHERE a.tenant_key = :tenantKey
                UNION ALL
                SELECT 'template', c.namespace, c.catalog_key::text, t.id::text, t.name, c.name, c.released
                FROM document_templates t JOIN editable c ON c.version_id = t.version_id
                WHERE t.tenant_key = :tenantKey
                UNION ALL
                SELECT 'codeList', c.namespace, c.catalog_key::text, l.id, l.display_name, c.name, c.released
                FROM current_code_lists l JOIN editable c ON c.version_id = l.version_id
                WHERE l.tenant_key = :tenantKey
                UNION ALL
                SELECT 'image', c.namespace, c.catalog_key::text, a.id::text, a.name, c.name, c.released
                FROM assets a JOIN editable c ON c.version_id = a.version_id
                WHERE a.tenant_key = :tenantKey
                UNION ALL
                SELECT 'font', c.namespace, c.catalog_key::text, f.slug::text, f.name, c.name, c.released
                FROM fonts f JOIN editable c ON c.version_id = f.version_id
                WHERE f.tenant_key = :tenantKey
                UNION ALL
                SELECT 'theme', c.namespace, c.catalog_key::text, th.id::text, th.name, c.name, c.released
                FROM current_themes th JOIN editable c ON c.version_id = th.version_id
                WHERE th.tenant_key = :tenantKey
            ) resources
            WHERE resource_type IN (<types>)
              AND (:search IS NULL OR resource_name ILIKE :search OR resource_key ILIKE :search)
              AND (:anyAddress OR resource_type || ':' || namespace || '/' || catalog_key || '/' || resource_key IN (<addresses>))
            ORDER BY catalog_name, resource_type, resource_name
            LIMIT :limit
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .bindList("types", types)
            .bind("search", query.search?.takeIf { it.isNotBlank() }?.let { "%$it%" })
            .bind("limit", query.limit.coerceIn(1, 200))
            .bind("anyAddress", query.addresses.isEmpty())
            // A list binding cannot be empty; the placeholder never matches and is ignored anyway.
            .bindList("addresses", query.addresses.map { it.id }.ifEmpty { listOf("") })
            .map { rs, _ ->
                RelocatableResource(
                    address = ResourceAddress(
                        CatalogResourceType.entries.single { it.wireName == rs.getString("resource_type") },
                        rs.getString("namespace"),
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
