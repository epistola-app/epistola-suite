// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.graph.TenantResourceGraphBuilder
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * What uses each resource of a catalog from other catalogs, by `type:key`: the **Used by** counts and
 * dialog. Read from the resource graph, the one reference authority ([TenantResourceGraphBuilder],
 * from the reference index), so every kind of reference counts: a template's theme, stencils and
 * images, a theme's fonts, a variant's attributes, an attribute's code list, a stencil's stencils.
 * What a catalog uses of itself is no outside use and is left out.
 */
data class FindResourceUsages(
    val catalog: CatalogId,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Query<Map<String, List<ResourceUsage>>>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.CATALOG_VIEW
}

/** One resource that uses another: its name, its catalog's key, and its type (`template`, `theme`, …). */
data class ResourceUsage(
    val referencedByName: String,
    val referencedByCatalog: String,
    val referenceType: String,
)

@Component
class FindResourceUsagesHandler(
    private val jdbi: Jdbi,
    private val graphs: TenantResourceGraphBuilder,
) : QueryHandler<FindResourceUsages, Map<String, List<ResourceUsage>>> {

    override fun handle(query: FindResourceUsages): Map<String, List<ResourceUsage>> = jdbi.withHandle<Map<String, List<ResourceUsage>>, Exception> { handle ->
        val graph = graphs.buildOn(handle, query.tenantKey)
        val names = graph.nodes.associate { it.address to it.name }
        val address = query.catalog.address
        graph.edges
            .filter { edge -> edge.target?.catalogAddress == address && edge.source.catalogAddress != address }
            .map { edge ->
                val target = edge.target!!
                "${target.type.wireName}:${target.key}" to ResourceUsage(
                    referencedByName = names[edge.source] ?: edge.source.key,
                    referencedByCatalog = edge.source.catalogKey,
                    referenceType = edge.source.type.wireName,
                )
            }
            .distinct()
            .sortedWith(compareBy({ it.first }, { it.second.referencedByCatalog }, { it.second.referencedByName }))
            .groupBy({ it.first }, { it.second })
    }
}
