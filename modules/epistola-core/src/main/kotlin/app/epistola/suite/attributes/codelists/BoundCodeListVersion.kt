// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists

import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.releases.VersionSelection
import app.epistola.suite.common.ids.CatalogId
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component

/**
 * Which version of a code list an attribute bound to it means (ADR 0033, step 3): one of the
 * attribute's own catalog is read where the attribute is; one of another catalog at what the
 * attribute's catalog declares for it, the release it pins or the draft it follows, as a release of
 * that catalog would resolve it. Null when the code list is read as the caller means it (its own
 * catalog, or one the declarations do not pin).
 */
@Component
class BoundCodeListVersion(
    private val dependencies: CatalogDependencyStore,
) {
    fun of(handle: Handle, boundFrom: CatalogId, codeListCatalog: CatalogId): VersionSelection? {
        if (boundFrom == codeListCatalog) return null
        val declaration = dependencies.declarations(handle, boundFrom).firstOrNull { it.catalogIn(boundFrom.tenantKey) == codeListCatalog } ?: return null
        declaration.releaseVersion?.let { return VersionSelection.Release(codeListCatalog, it) }
        val draft = declaration.draftId ?: return null
        return dependencies.draftOf(handle, boundFrom.tenantKey, declaration, draft)?.let { (versionId, _) -> VersionSelection.Draft(versionId) }
    }
}
