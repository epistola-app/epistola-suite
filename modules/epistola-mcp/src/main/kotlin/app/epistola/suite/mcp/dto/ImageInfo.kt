// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.assets.Asset
import app.epistola.suite.catalog.CatalogType
import java.time.OffsetDateTime

/** Metadata for an image available to templates, excluding its binary content. */
data class ImageInfo(
    val id: String,
    val catalogId: String,
    val catalogType: String,
    val readOnly: Boolean,
    val name: String,
    val mediaType: String,
    val sizeBytes: Long,
    val width: Int?,
    val height: Int?,
    val createdAt: OffsetDateTime,
) {
    companion object {
        fun from(image: Asset): ImageInfo = ImageInfo(
            id = image.id.value.toString(),
            catalogId = image.catalogKey.value,
            catalogType = image.catalogType.name,
            readOnly = image.catalogType == CatalogType.SUBSCRIBED,
            name = image.name,
            mediaType = image.mediaType.mimeType,
            sizeBytes = image.sizeBytes,
            width = image.width,
            height = image.height,
            createdAt = image.createdAt,
        )
    }
}
