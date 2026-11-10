// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.queries.ListVisibleCatalogs
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.mediator.query

/**
 * What a picker in version [version] of [catalog] offers: [list] of each catalog that version can use
 * ([ListVisibleCatalogs]), its own first, each read at the version its declaration names. Never the
 * tenant's every catalog, which would offer what the version does not render with.
 */
internal fun <T> acrossVisibleCatalogs(catalog: CatalogId, version: CatalogReleaseRef?, list: (CatalogId, CatalogReleaseRef?) -> List<T>): List<T> = ListVisibleCatalogs(catalog, version?.takeIf { it.catalog == catalog }).query().catalogs
    .flatMap { list(it.catalog, it.version) }
