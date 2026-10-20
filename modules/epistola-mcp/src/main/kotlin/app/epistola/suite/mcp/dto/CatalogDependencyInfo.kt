// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration

/**
 * A catalog the catalog depends on: named by [registry], [namespace] and [catalogId], and pinned to the
 * exact release [release], or, in a draft, building on its draft [draftId] ([draftName]). Both null for a
 * declaration that pins nothing yet, which releasing refuses.
 */
data class CatalogDependencyInfo(
    val catalogId: String,
    val namespace: String,
    val registry: String,
    val release: String?,
    val draftId: String?,
    val draftName: String?,
) {
    companion object {
        fun from(declaration: CatalogDependencyDeclaration) = CatalogDependencyInfo(
            catalogId = declaration.catalogKey,
            namespace = declaration.namespace,
            registry = declaration.registry,
            release = declaration.releaseVersion,
            draftId = declaration.draftId,
            draftName = declaration.draftName,
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
