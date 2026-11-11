// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.mediator.query

/**
 * A resource of a catalog this one depends on, listed read-only on this catalog's list of that kind
 * (docs/workspace-shell.md): templates here can use it, and it is changed in its own catalog.
 */
data class FromOtherCatalog(val name: String, val href: String, val catalogName: String)

object FromOtherCatalogs {
    /**
     * What [bar]'s version can use of one kind from the catalogs it depends on, each made a row by
     * [row]. [visible] is that kind's `ListVisible…` query (`Reach.DECLARED`): it reads each
     * dependency at the version the declaration pins, as the editor and rendering do, not at its
     * next version; [catalogOf] names the catalog a resource is in, so this catalog's own are left
     * out. Empty outside a catalog.
     */
    fun <T> of(
        bar: CatalogDraftBar?,
        visible: (CatalogId, CatalogReleaseRef?) -> List<T>,
        catalogOf: (T) -> CatalogId,
        row: (T, CatalogId, String) -> FromOtherCatalog,
    ): List<FromOtherCatalog> {
        bar ?: return emptyList()
        val others = visible(bar.catalog, bar.ref).filter { catalogOf(it) != bar.catalog }
        if (others.isEmpty()) return emptyList()
        val names = ListCatalogs(bar.tenantKey).query().associate { it.catalogId to it.name }
        return others
            .map { resource -> catalogOf(resource).let { catalog -> row(resource, catalog, names[catalog] ?: catalog.key.value) } }
            .sortedWith(compareBy({ it.catalogName.lowercase() }, { it.name.lowercase() }))
    }
}
