// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.api.model.AssetDto
import app.epistola.suite.assets.Asset
import app.epistola.suite.assets.AssetMediaCategory
import app.epistola.suite.catalog.CatalogType

internal fun Asset.toDto() = AssetDto(
    id = id.value,
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
