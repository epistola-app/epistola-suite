// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.queries.ReleaseContentHashes

/**
 * A catalog the catalog depends on: named by [registry], [namespace] and [catalogId], and pinned to the
 * exact release [release], or, in a draft, building on its draft [draftId] ([draftName]). Both null for a
 * declaration that pins nothing yet, which releasing refuses. [alias] is what the catalog's content names
 * it by (a reference's `catalogKey`, contract ADR 0003): its key, in this installation. [contentHash] is
 * the pinned release's content hash (ADR 0034), as REST's `CatalogDependencyDto`; null while it pins no
 * release, or for one without a content hash yet.
 */
data class CatalogDependencyInfo(
    val catalogId: String,
    val alias: String,
    val namespace: String,
    val registry: String,
    val release: String?,
    val draftId: String?,
    val draftName: String?,
    val contentHash: String? = null,
) {
    companion object {
        fun from(declaration: CatalogDependencyDeclaration, hashes: ReleaseContentHashes) = CatalogDependencyInfo(
            catalogId = declaration.catalogKey,
            alias = declaration.alias,
            namespace = declaration.namespace,
            registry = declaration.registry,
            release = declaration.releaseVersion,
            draftId = declaration.draftId,
            draftName = declaration.draftName,
            contentHash = hashes.pinnedBy(declaration),
        )
    }
}

/**
 * One dependency to declare: a catalog of this tenant, by key, and the exact [release] of it to render
 * with, or a draft of it by [draftId]. Neither: its highest release.
 */
data class CatalogDependencyInput(
    val catalogId: String,
    val release: String? = null,
    val draftId: String? = null,
)
