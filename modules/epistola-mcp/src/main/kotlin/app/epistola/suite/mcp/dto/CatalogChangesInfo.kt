// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.catalog.queries.CatalogResourceChanges

/**
 * What the next release of a catalog that can be changed here would contain that its last one did not, resource by
 * resource, whether each changed resource is marked ready, and whether it is merged content a merge
 * wrote that nobody has marked ready since ([Resource.mergedForReview]); and what changed beside the
 * resources ([settings]). [releasable], as REST `getCatalogChanges`: something changed against the
 * base (or there is none yet), no changed resource is unready, and no merged content awaits review.
 * [dependenciesChanged]: a catalog it uses was added, removed or pinned to another release.
 */
data class CatalogChangesInfo(
    val catalogId: String,
    val latestVersion: String?,
    val releasable: Boolean,
    val resources: List<Resource>,
    /**
     * What changed beside the resources, which the release's content hash covers too (ADR 0034): each
     * catalog it uses, its default theme, presets and license. Only changes are listed; none needs a
     * ready mark.
     */
    val settings: List<Setting> = emptyList(),
    val dependenciesChanged: Boolean = false,
) {
    /**
     * One resource; [state] is `NEW`, `MODIFIED`, `RELEASED` or `REMOVED`. [mergedForReview]: a merge
     * wrote it as merged content, neither side's, and it is to be reviewed with the user before release.
     */
    data class Resource(val type: String, val key: String, val name: String?, val state: String, val ready: Boolean, val mergedForReview: Boolean = false)

    /**
     * One catalog setting that changed. [entry] is `catalog/defaultTheme`, `catalog/presets`,
     * `catalog/license` or `catalogDependency/<alias>`; [state] is `NEW`, `MODIFIED` or `REMOVED`.
     * For a dependency, [releaseVersion] or [draftName] is what it renders with now (both null while
     * nothing is chosen, or once removed) and [baseReleaseVersion] what the base pinned.
     */
    data class Setting(
        val entry: String,
        val state: String,
        val name: String,
        val releaseVersion: String? = null,
        val draftName: String? = null,
        val baseReleaseVersion: String? = null,
    )

    companion object {
        fun from(changes: CatalogResourceChanges) = CatalogChangesInfo(
            catalogId = changes.catalogKey.value,
            latestVersion = changes.latestVersion,
            releasable = (changes.latestVersion == null || changes.hasUnreleasedChanges) && changes.notReady.isEmpty() && changes.mergedForReview.isEmpty(),
            resources = changes.resources.map { Resource(it.type, it.slug, it.name, it.state.name, it.ready, it.mergedForReview) },
            settings = changes.settings.map { Setting(it.entry, it.state.name, it.name, it.lock?.releaseVersion, it.lock?.draftName, it.baseLock?.releaseVersion) },
            dependenciesChanged = changes.settings.any { it.isDependency },
        )
    }
}
