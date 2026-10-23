// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.CatalogAddress
import app.epistola.suite.catalog.queries.CatalogEditabilities
import app.epistola.suite.catalog.queries.CatalogEditability
import app.epistola.suite.catalog.queries.ListCatalogEditability
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.query

/**
 * Whether a tenant's catalogs can be changed here (ADR 0032), for the rows of a list: a row offers
 * its edit and delete controls when `catalogEditing.editable(row.catalogKey)` is true. Every tenant
 * view gets one as `catalogEditing` ([CommonViewModel]); it reads the catalogs once, on the first
 * question, so a page that never asks pays nothing and a list of a hundred rows pays one query.
 */
class CatalogEditingView(private val tenantKey: TenantKey) {
    private val all: CatalogEditabilities by lazy { ListCatalogEditability(tenantKey).query() }

    /**
     * Whether the catalog [catalogKey] can be changed here. A key or its text: a template reading a
     * row's `catalogKey` gets the text, since a value class reaches it unboxed. Or `namespace/key`,
     * which names one of two catalogs that share a key (ADR 0034).
     */
    fun editable(catalogKey: Any?): Boolean = editability(catalogKey)?.editable ?: false

    /** The short reason a catalog cannot be changed here ("Supplied by Epistola"); null when it can. */
    fun readOnlyLabel(catalogKey: Any?): String? = editability(catalogKey)?.reason?.label

    private fun editability(catalog: Any?): CatalogEditability? {
        val text = catalog?.toString() ?: return null
        val key = CatalogKey.validateOrNull(text.substringAfter('/')) ?: return null
        return if ('/' in text) all.of(CatalogAddress(text.substringBefore('/'), key)) else all.of(key)
    }
}
