// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.catalog.queries.CatalogResourceChanges

/**
 * What the next release of a catalog that can be changed here would contain that its last one did not, resource by
 * resource, whether each changed resource is marked ready, and whether it is merged content a merge
 * wrote that nobody has marked ready since ([Resource.mergedForReview]). [releasable] is false while a
 * changed resource is not ready.
 */
data class CatalogChangesInfo(
    val catalogId: String,
    val latestVersion: String?,
    val releasable: Boolean,
    val resources: List<Resource>,
) {
    /**
     * One resource; [state] is `NEW`, `MODIFIED`, `RELEASED` or `REMOVED`. [mergedForReview]: a merge
     * wrote it as merged content, neither side's, and it is to be reviewed with the user before release.
     */
    data class Resource(val type: String, val key: String, val name: String?, val state: String, val ready: Boolean, val mergedForReview: Boolean = false)

    companion object {
        fun from(changes: CatalogResourceChanges) = CatalogChangesInfo(
            catalogId = changes.catalogKey.value,
            latestVersion = changes.latestVersion,
            releasable = changes.notReady.isEmpty(),
            resources = changes.resources.map { Resource(it.type, it.slug, it.name, it.state.name, it.ready, it.mergedForReview) },
        )
    }
}
