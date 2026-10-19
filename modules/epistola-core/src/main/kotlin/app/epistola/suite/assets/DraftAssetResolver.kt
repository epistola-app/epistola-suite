// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets

import app.epistola.catalog.protocol.ImageResource
import app.epistola.generation.pdf.AssetResolution
import app.epistola.generation.pdf.AssetResolver
import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.catalog.dependencies.DraftDependencyView
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.DraftRef
import app.epistola.suite.common.ids.AssetKey
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
    tenantKey: TenantKey,
    catalogVersion: CatalogVersionRef?,
    dependencies: DraftDependencyView?,
): AssetResolver = AssetResolver { assetId, assetCatalog ->
    val catalog = assetCatalog?.let(CatalogKey::of)
    if (catalog != null && dependencies?.releaseOf(catalog) != null) {
        val image = dependencies.released(catalog, "image", assetId) as? ImageResource ?: return@AssetResolver null
        return@AssetResolver dependencies.releasedBinary(image.contentHash)?.let { AssetResolution(it, image.mediaType) }
    }
    val namedDraft = catalog?.let { dependencies?.draftOf(it) }?.let { CatalogVersionRef(tenantKey, catalog, DraftRef.Id(it.key)) }
    GetAssetContent(tenantKey, AssetKey.of(assetId), catalog, catalogVersion = namedDraft ?: catalogVersion).query()
        ?.let { AssetResolution(it.content, it.mediaType.mimeType) }
}
