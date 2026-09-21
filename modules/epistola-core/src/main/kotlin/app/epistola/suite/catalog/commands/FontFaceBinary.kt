// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.FontVariantEntry
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute

/**
 * How much of a face binary's content hash becomes its assets-row key.
 *
 * Long enough that a collision within one tenant is not a practical concern, short enough for
 * ASSET_KEY. The key is internal: a face's binary is not addressable on the wire from v7, so
 * nothing outside the suite ever names it.
 */
internal const val FACE_ASSET_KEY_LENGTH = 32

/** The media type a font face's archive path implies. */
internal fun faceMediaType(contentUrl: String): String = when (contentUrl.substringAfterLast('.').lowercase()) {
    "otf" -> "font/otf"
    else -> "font/ttf"
}

/**
 * Puts a font face's binary in the assets table and returns the key it landed under.
 *
 * A face names no asset on the wire from catalog v7, but `font_variants` still points at an
 * `assets` row. The key comes from the content hash rather than being generated, so re-importing
 * the same catalog reuses the row instead of leaving a new one behind on every upgrade.
 */
internal fun materialiseFontFace(
    tenantId: TenantId,
    catalogKey: CatalogKey,
    fontSlug: String,
    face: FontVariantEntry,
    bytes: ByteArray,
): AssetKey {
    val key = AssetKey.of(face.contentHash.take(FACE_ASSET_KEY_LENGTH))
    ImportAsset(
        tenantId = tenantId,
        catalogKey = catalogKey,
        id = key,
        name = "$fontSlug ${face.weight}${if (face.italic) " italic" else ""}",
        mediaType = AssetMediaType.fromMimeType(face.mediaType),
        content = bytes,
    ).execute()
    return key
}
