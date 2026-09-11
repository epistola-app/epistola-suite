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
    // `AssetDto.id` is a UUID on the wire while an asset's key is now text. Safe today because
    // nothing can create an asset whose key is not a UUID: uploads generate one, and the catalog
    // importers still parse an incoming slug as a UUID and refuse anything else. The contract gains
    // a text `key` alongside a deprecated `id` in epistola-app/epistola-contract#78; the importers
    // are relaxed once this can serve such an asset without throwing here.
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
