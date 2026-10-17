// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.catalog.queries.CatalogResourceChanges

/**
 * What an authored catalog's next release would contain that its last one did not, resource by
 * resource, and whether each changed resource is marked ready. [releasable] is false while a changed
 * resource is not ready.
 */
data class CatalogChangesInfo(
    val catalogId: String,
    val latestVersion: String?,
    val releasable: Boolean,
    val resources: List<Resource>,
) {
    /** One resource; [state] is `NEW`, `MODIFIED`, `RELEASED`, `REMOVED` or `UNKNOWN`. */
    data class Resource(val type: String, val key: String, val name: String?, val state: String, val ready: Boolean)

    companion object {
        fun from(changes: CatalogResourceChanges) = CatalogChangesInfo(
            catalogId = changes.catalogKey.value,
            latestVersion = changes.latestVersion,
            releasable = changes.notReady.isEmpty(),
            resources = changes.resources.map { Resource(it.type, it.slug, it.name, it.state.name, it.ready) },
        )
    }
}
