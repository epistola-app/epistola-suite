// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.queries

import app.epistola.suite.catalog.CatalogEditingProperties
import app.epistola.suite.catalog.dependencies.Reach
import app.epistola.suite.catalog.dependencies.VisibleVersions
import app.epistola.suite.catalog.dependencies.VisibleVersions.bindVisible
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.STENCIL_COLUMNS
import app.epistola.suite.stencils.Stencil
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * The stencils one version of [catalog] can use ([VisibleVersions]): its own catalog's, then those of
 * each catalog it declares, at the release or draft the declaration names; within a catalog by name. With
 * [reach] [Reach.UNDECLARED], those of the catalogs it could declare instead, each at the version
 * declaring it names: what a picker offers apart, to be declared before it is used.
 */
data class ListVisibleStencils(
    val catalog: CatalogId,
    /** The catalog version this addresses; null for its catalog's next version. */
    override val catalogVersion: CatalogReleaseRef?,
    /** Only those whose name or description contains it, ignoring case; null or blank for all. */
    val searchTerm: String? = null,
    /** The catalogs it can use, or those it could declare. */
    val reach: Reach = Reach.DECLARED,
) : ReleaseReadable,
    Query<List<Stencil>>,
    RequiresPermission {
    override val permission get() = Permission.STENCIL_VIEW
    override val tenantKey get() = catalog.tenantKey
}

@Component
class ListVisibleStencilsHandler(
    private val jdbi: Jdbi,
    private val editing: CatalogEditingProperties,
) : QueryHandler<ListVisibleStencils, List<Stencil>> {
    override fun handle(query: ListVisibleStencils): List<Stencil> = jdbi.withHandle<List<Stencil>, Exception> { handle ->
        val visible = VisibleVersions.reach(handle, query.catalog, query, query.reach, editing.enabled)
        if (visible.isEmpty()) return@withHandle emptyList()
        handle.createQuery(
            "SELECT $STENCIL_COLUMNS FROM version_stencils s WHERE s.tenant_key = :t AND ${VisibleVersions.predicate("s")}${if (query.searchTerm.isNullOrBlank()) "" else " AND (s.name ILIKE :searchTerm OR s.description ILIKE :searchTerm)"} " +
                "ORDER BY ${VisibleVersions.order("s")}, s.name ASC, s.id",
        )
            .bind("t", query.tenantKey)
            .bindVisible(visible)
            .apply { if (!query.searchTerm.isNullOrBlank()) bind("searchTerm", "%${query.searchTerm}%") }
            .mapTo<Stencil>()
            .list()
    }
}
