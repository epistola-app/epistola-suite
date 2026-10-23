// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.api.model.ImageDto
import app.epistola.suite.assets.Asset
import app.epistola.suite.catalog.Catalog

/**
 * An image as the API describes it.
 *
 * `slug` is the stored key verbatim -- a plain string, not a UUID. That is the whole point of the
 * images API: an image from a catalog published elsewhere may be named `municipality-mark`, which
 * the asset operations cannot express because their `id` is declared `format: uuid`.
 */
internal fun Asset.toImageDto(catalog: Catalog, readOnly: Boolean = catalog.readOnlyHere()) = ImageDto(
    key = id.value,
    tenantKey = tenantKey.value,
    registry = catalog.registry,
    namespace = catalog.namespace,
    catalogKey = catalogKey.value,
    readOnly = readOnly,
    name = name,
    mediaType = mediaType.mimeType,
    sizeBytes = sizeBytes,
    createdAt = createdAt,
    width = width,
    height = height,
)
