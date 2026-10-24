// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets

import app.epistola.catalog.protocol.ImageResource
import app.epistola.generation.pdf.AssetResolution
import app.epistola.generation.pdf.AssetResolver
import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.catalog.dependencies.DraftDependencyView
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.query

/**
 * Images for rendering a draft: one that names its catalog comes from where the draft's
 * [dependencies] say (the release it pins, or the draft it names), every other one as
 * [GetAssetContent] finds it in [catalogVersion], the draft being rendered. The mediator must be
 * bound on the calling thread.
 */
fun draftAssetResolver(
    /** The catalog of the content the references are written in: they name other catalogs by alias from it (ADR 0034, section 9). */
    owning: CatalogId,
    catalogVersion: CatalogReleaseRef?,
    dependencies: DraftDependencyView,
): AssetResolver = AssetResolver { assetId, alias ->
    // An image that names no catalog is found by its id, which is the tenant's.
    val catalog = alias?.let { dependencies.resolve(owning, it) ?: return@AssetResolver null }
    if (catalog != null && dependencies.releaseOf(catalog) != null) {
        val image = dependencies.released(catalog, "image", assetId) as? ImageResource ?: return@AssetResolver null
        return@AssetResolver dependencies.releasedBinary(image.contentHash)?.let { AssetResolution(it, image.mediaType) }
    }
    val namedDraft = catalog?.let { dependencies.draftOf(it) }?.let { CatalogReleaseRef(catalog, DraftRef.Id(it.key)) }
    GetAssetContent(owning.tenantKey, AssetKey.of(assetId), catalog, catalogVersion = namedDraft ?: catalogVersion).query()
        ?.let { AssetResolution(it.content, it.mediaType.mimeType) }
}
