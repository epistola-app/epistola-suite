// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists

import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.releases.ContentVersion
import app.epistola.suite.catalog.releases.ContentVersions
import app.epistola.suite.common.ids.CatalogId
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component

/**
 * Which version of a code list an attribute bound to it means (ADR 0033, step 3): one of the
 * attribute's own catalog is read in the attribute's version [boundFrom]; one of another catalog at
 * what version [boundFrom] declares for it, the release it pins or the draft it follows, as a release
 * of that catalog would resolve it. Null when the declarations do not pin the list's catalog: the
 * caller then reads the version its message addresses there, else that catalog's next version.
 */
@Component
class BoundCodeListVersion(
    private val dependencies: CatalogDependencyStore,
) {
    fun of(handle: Handle, boundFrom: ContentVersion, codeListCatalog: CatalogId): ContentVersion? {
        if (boundFrom.catalog == codeListCatalog) return boundFrom
        val declaration = dependencies.declarations(handle, boundFrom).firstOrNull { it.catalogIn(boundFrom.tenantKey) == codeListCatalog } ?: return null
        declaration.releaseVersion?.let { return ContentVersion(codeListCatalog, ContentVersions.release(handle, codeListCatalog, it)) }
        val draft = declaration.draftId ?: return null
        return dependencies.draftOf(handle, boundFrom.tenantKey, declaration, draft)?.let { (versionId, _) -> ContentVersion(codeListCatalog, versionId) }
    }
}
