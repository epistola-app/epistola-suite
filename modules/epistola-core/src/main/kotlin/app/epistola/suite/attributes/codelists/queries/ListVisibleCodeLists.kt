// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists.queries

import app.epistola.suite.attributes.codelists.CODE_LIST_COLUMNS
import app.epistola.suite.attributes.codelists.model.CodeList
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
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * The code lists one version of [catalog] can use ([VisibleVersions]): its own catalog's, then those of
 * each catalog it declares, at the release or draft the declaration names; within a catalog by name. With
 * [reach] [Reach.UNDECLARED], those of the catalogs it could declare instead, each at the version
 * declaring it names: what a picker offers apart, to be declared before it is used.
 */
data class ListVisibleCodeLists(
    val catalog: CatalogId,
    /** The catalog version this addresses; null for its catalog's next version. */
    override val catalogVersion: CatalogReleaseRef?,
    /** The catalogs it can use, or those it could declare. */
    val reach: Reach = Reach.DECLARED,
) : ReleaseReadable,
    Query<List<CodeList>>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = catalog.tenantKey
}

@Component
class ListVisibleCodeListsHandler(
    private val jdbi: Jdbi,
    private val editing: CatalogEditingProperties,
) : QueryHandler<ListVisibleCodeLists, List<CodeList>> {
    override fun handle(query: ListVisibleCodeLists): List<CodeList> = jdbi.withHandle<List<CodeList>, Exception> { handle ->
        val visible = VisibleVersions.reach(handle, query.catalog, query, query.reach, editing.enabled)
        if (visible.isEmpty()) return@withHandle emptyList()
        handle.createQuery(
            "SELECT $CODE_LIST_COLUMNS FROM version_code_lists t WHERE t.tenant_key = :t AND ${VisibleVersions.predicate("t")} " +
                "ORDER BY ${VisibleVersions.order("t")}, t.display_name ASC, t.id",
        )
            .bind("t", query.tenantKey)
            .bindVisible(visible)
            .mapTo<CodeList>()
            .list()
    }
}
