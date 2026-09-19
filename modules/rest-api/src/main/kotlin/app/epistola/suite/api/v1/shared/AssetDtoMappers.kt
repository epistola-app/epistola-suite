// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.api.model.AssetDto
import app.epistola.suite.assets.Asset
import app.epistola.suite.assets.AssetMediaCategory
import app.epistola.suite.catalog.CatalogType
import java.util.UUID

internal fun Asset.toDto() = AssetDto(
    // `AssetDto.id` is declared `format: uuid`, so the generated field is a `java.util.UUID` while
    // an asset's key is now text. Safe today because nothing can create an asset whose key is not a
    // UUID: uploads generate one, and the catalog importers still parse an incoming slug as a UUID
    // and refuse anything else. This endpoint is deprecated in favour of `/images`, whose
    // `ImageDto.slug` is a plain string; the importers are relaxed once the Suite serves that and
    // this mapper no longer has to represent every asset (epistola-app/epistola-contract#79).
    id = UUID.fromString(id.value),
    tenantId = tenantKey.value,
    catalog = catalogKey.value,
    catalogType = when (catalogType) {
        CatalogType.AUTHORED -> AssetDto.CatalogType.AUTHORED
        CatalogType.SUBSCRIBED -> AssetDto.CatalogType.SUBSCRIBED
    },
    readOnly = catalogType == CatalogType.SUBSCRIBED,
    name = name,
    mediaType = mediaType.mimeType,
    mediaCategory = when (mediaType.category) {
        AssetMediaCategory.IMAGE -> AssetDto.MediaCategory.IMAGE
        AssetMediaCategory.FONT -> AssetDto.MediaCategory.FONT
        AssetMediaCategory.OTHER -> AssetDto.MediaCategory.OTHER
    },
    sizeBytes = sizeBytes,
    createdAt = createdAt,
    width = width,
    height = height,
)
