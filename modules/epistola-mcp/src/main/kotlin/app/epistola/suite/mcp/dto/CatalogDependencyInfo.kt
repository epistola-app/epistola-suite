// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration

/**
 * A catalog the catalog depends on: named by [registry], [namespace] and [catalogId], selected by
 * the npm range [version], and locked to [lockedRelease], the release the next release is cut with.
 * Null while no release of it matches.
 */
data class CatalogDependencyInfo(
    val catalogId: String,
    val namespace: String,
    val registry: String,
    val version: String,
    val lockedRelease: String?,
) {
    companion object {
        fun from(declaration: CatalogDependencyDeclaration) = CatalogDependencyInfo(
            catalogId = declaration.catalogKey,
            namespace = declaration.namespace,
            registry = declaration.registry,
            version = declaration.version,
            lockedRelease = declaration.releaseVersion,
        )
    }
}

/** One dependency to declare: a catalog of this tenant, by key, and a version selector. */
data class CatalogDependencyInput(
    val catalogId: String,
    val version: String = "*",
)
