// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.catalog.queries.CatalogReadOnlyReason
import app.epistola.suite.catalog.queries.GetCatalogEditability
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.query

/** A change to a catalog that cannot be changed here (ADR 0032); [reason] says why. */
open class CatalogReadOnlyException(
    val catalogKey: CatalogKey,
    val reason: CatalogReadOnlyReason? = null,
    message: String = "Catalog '${catalogKey.value}' cannot be changed here." + (reason?.let { " ${it.explanation}" } ?: ""),
) : RuntimeException(message)

/**
 * A command that targets a released version (ADR 0033): a release is read-only, whatever the
 * catalog's editability. The write guard in the mediator refuses it before any handler runs.
 */
class ReleasedVersionReadOnlyException(val catalog: CatalogId, val version: String) : CatalogReadOnlyException(catalog.key, null, "Release $version of catalog '${catalog.address}' cannot be changed: a release is read-only. Start a draft from it to change it.")

/**
 * Checks that the given catalog can be changed in this installation ([GetCatalogEditability]).
 * Throws [CatalogReadOnlyException] if not. Call this at the start of any command handler that
 * changes a catalog. Receiving a release (an install, upgrade or import) is not a change people
 * make, and passes.
 */
fun requireCatalogEditable(catalog: CatalogId) {
    if (CatalogImportContext.isImporting) return
    val editability = GetCatalogEditability(catalog).query()
    if (!editability.editable) throw CatalogReadOnlyException(catalog.key, editability.reason)
}
