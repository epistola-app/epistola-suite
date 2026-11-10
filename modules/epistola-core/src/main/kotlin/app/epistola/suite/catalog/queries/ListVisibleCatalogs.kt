// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogEditingProperties
import app.epistola.suite.catalog.dependencies.Reach
import app.epistola.suite.catalog.dependencies.VisibleVersion
import app.epistola.suite.catalog.dependencies.VisibleVersions
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * The catalogs one version of [catalog] can use ([VisibleVersions]), each at the version it reads:
 * itself first, then each catalog it declares. With [reach] [Reach.UNDECLARED], the catalogs it could
 * declare instead, at the version declaring each would name. What a picker that chooses a catalog
 * first (the editor's image picker) offers, a catalog with nothing in it yet included.
 */
data class ListVisibleCatalogs(
    val catalog: CatalogId,
    /** The catalog version this addresses; null for its catalog's next version. */
    override val catalogVersion: CatalogReleaseRef?,
    /** The catalogs it can use, or those it could declare. */
    val reach: Reach = Reach.DECLARED,
) : ReleaseReadable,
    Query<List<VisibleVersion>>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = catalog.tenantKey
}

@Component
class ListVisibleCatalogsHandler(
    private val jdbi: Jdbi,
    private val editing: CatalogEditingProperties,
) : QueryHandler<ListVisibleCatalogs, List<VisibleVersion>> {
    override fun handle(query: ListVisibleCatalogs): List<VisibleVersion> = jdbi.withHandle<List<VisibleVersion>, Exception> { handle ->
        VisibleVersions.reach(handle, query.catalog, query, query.reach, editing.enabled)
    }
}
