// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogReferences
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.springframework.stereotype.Component

/**
 * The catalog [alias] names in content of [from] ([CatalogReferences.resolve], ADR 0034, section 9):
 * [from] itself for its own key, else the catalog its current version declares under the alias;
 * null when it declares none. What a page asks when content it shows names another catalog by
 * alias, as the editor's images do.
 */
data class ResolveCatalogAlias(
    val from: CatalogId,
    val alias: String,
) : Query<CatalogId?>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = from.tenantKey
    override val permission get() = Permission.TEMPLATE_VIEW
}

@Component
class ResolveCatalogAliasHandler(
    private val references: CatalogReferences,
) : QueryHandler<ResolveCatalogAlias, CatalogId?> {
    override fun handle(query: ResolveCatalogAlias): CatalogId? = references.resolve(query.from, query.alias)
}
