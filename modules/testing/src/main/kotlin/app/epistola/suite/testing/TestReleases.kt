// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.queries.GetLatestCatalogRelease
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.templates.contracts.queries.GetDataContract

/**
 * Marks [variantId]'s catalog ready and cuts its next release, so generation can render the variant.
 *
 * From 2.0 generation renders a catalog release and nothing else, so a test that generates a document
 * needs one. The template gets the minimum releasable data contract first, because marking it ready
 * requires an example.
 *
 * @return the version cut.
 */
fun Mediator.publishAndRelease(variantId: VariantId): String {
    // Only when the template has no example yet: a test that set its own contract keeps it.
    if (query(GetDataContract(variantId.templateId))?.dataExamples.isNullOrEmpty()) {
        ensureRequiredDataExample(variantId.templateId)
    }
    return releaseNext(variantId.templateId.catalogId)
}

/**
 * Marks every changed resource of [catalogId] ready and releases its next version under the number its
 * changes need (`1.0.0` for its first), as an author releasing what they finished would.
 *
 * @return the version cut.
 */
fun Mediator.releaseNext(catalogId: CatalogId): String {
    send(MarkCatalogReady(catalogId.tenantKey, catalogId.key))
    return send(ReleaseCatalogDraft(tenantKey = catalogId.tenantKey, catalogKey = catalogId.key)).version
}

/**
 * Deploys the latest release of [catalogId] to [environmentId], cutting the catalog's first release
 * when it has none. Environments serve catalog releases from 2.0; this replaces activating a version.
 *
 * @return the version deployed.
 */
fun Mediator.deployLatestRelease(environmentId: app.epistola.suite.common.ids.EnvironmentId, catalogId: CatalogId): String {
    val version = query(GetLatestCatalogRelease(catalogId.tenantKey, catalogId.key)).latestVersion ?: releaseNext(catalogId)
    send(app.epistola.suite.environments.commands.DeployCatalogRelease(environmentId, catalogId.key, version))
    return version
}

/**
 * Exports [catalogId] as a catalog archive, as an author handing it over would: its next version is
 * released first when it holds anything not released yet, along with the drafts of other catalogs it
 * builds on, since only releases are exported (contract 2.0).
 */
fun Mediator.exportReleased(catalogId: CatalogId, includeDependencies: Boolean = false): app.epistola.suite.catalog.commands.ExportCatalogZipResult {
    requireNotNull(query(app.epistola.suite.catalog.queries.GetCatalog(catalogId.tenantKey, catalogId.key))) { "No catalog ${catalogId.key.value}" }
    if (!query(app.epistola.suite.catalog.queries.GetCatalogEditability(catalogId.tenantKey, catalogId.key)).editable) {
        // A catalog that cannot be changed here (Epistola supplies it) exports its highest release.
        return send(app.epistola.suite.catalog.commands.ExportCatalogZip(catalogId.tenantKey, catalogId.key, includeDependencies = includeDependencies))
    }
    val unreleased = query(app.epistola.suite.catalog.queries.GetCatalogResourceChanges(catalogId.tenantKey, catalogId.key)).resources
        .any { it.state != app.epistola.suite.catalog.queries.CatalogResourceState.RELEASED }
    val version = query(GetLatestCatalogRelease(catalogId.tenantKey, catalogId.key)).latestVersion?.takeUnless { unreleased } ?: run {
        // Releasing along the drafts of other catalogs it builds on, as the author would be offered,
        // each marked ready first, as their authors would have.
        markDraftDependenciesReady(catalogId.tenantKey, catalogId.key, null, mutableSetOf())
        send(MarkCatalogReady(catalogId.tenantKey, catalogId.key))
        send(ReleaseCatalogDraft(tenantKey = catalogId.tenantKey, catalogKey = catalogId.key, releaseDependencies = true)).version
    }
    return send(app.epistola.suite.catalog.commands.ExportCatalogZip(catalogId.tenantKey, catalogId.key, version, includeDependencies = includeDependencies))
}

/** Marks ready every draft of another catalog that [catalogKey]'s declarations name, transitively. */
private fun Mediator.markDraftDependenciesReady(
    tenantKey: app.epistola.suite.common.ids.TenantKey,
    catalogKey: app.epistola.suite.common.ids.CatalogKey,
    at: app.epistola.suite.catalog.releases.CatalogReleaseRef?,
    seen: MutableSet<String>,
) {
    query(app.epistola.suite.catalog.queries.ListCatalogDependencies(tenantKey, catalogKey, at)).filter { it.namesDraft }.forEach { declaration ->
        val key = app.epistola.suite.common.ids.CatalogKey.of(declaration.catalogKey)
        val ref = app.epistola.suite.catalog.releases.CatalogReleaseRef(tenantKey, key, app.epistola.suite.catalog.releases.DraftRef.Id(requireNotNull(declaration.draftId)))
        if (!seen.add("${declaration.catalogKey}/${declaration.draftId}")) return@forEach
        markDraftDependenciesReady(tenantKey, key, ref, seen)
        send(MarkCatalogReady(tenantKey, key, catalogVersion = ref))
    }
}
