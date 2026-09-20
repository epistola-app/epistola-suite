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
    // `AssetDto.id` is declared `format: uuid`, so the generated field is a `java.util.UUID` and
    // cannot carry a readable key such as `municipality-mark`. The field is optional now, so an
    // image this endpoint cannot name is listed without one rather than failing the whole
    // response. `/images` reports every image by `slug`, which is a plain string.
    id = runCatching { UUID.fromString(id.value) }.getOrNull(),
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
