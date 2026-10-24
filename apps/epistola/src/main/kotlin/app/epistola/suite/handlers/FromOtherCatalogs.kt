// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.mediator.query

/**
 * A resource of a catalog this one depends on, listed read-only on this catalog's list of that kind
 * (docs/workspace-shell.md): templates here can use it, and it is changed in its own catalog.
 */
data class FromOtherCatalog(val name: String, val href: String, val catalogName: String)

object FromOtherCatalogs {
    /**
     * What the catalogs [bar]'s version depends on hold, as [list] finds it in each (its current
     * version, as the editor resolves it), each made a row by [row]. Empty outside a catalog, and
     * for a dependency this tenant does not have.
     */
    fun <T> of(
        bar: CatalogDraftBar?,
        list: (app.epistola.suite.common.ids.CatalogId) -> List<T>,
        row: (T, app.epistola.suite.common.ids.CatalogId, String) -> FromOtherCatalog,
    ): List<FromOtherCatalog> {
        bar ?: return emptyList()
        val local = ListCatalogs(bar.tenantKey).query()
        return ListCatalogDependencies(bar.catalog, bar.ref).query()
            .mapNotNull { dependency -> local.firstOrNull { it.catalogId == dependency.catalogIn(bar.tenantKey) } }
            .flatMap { catalog -> list(catalog.catalogId).map { row(it, catalog.catalogId, catalog.name) } }
            .sortedWith(compareBy({ it.catalogName.lowercase() }, { it.name.lowercase() }))
    }
}
