// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.api.model.FontDto
import app.epistola.api.model.FontVariantDto
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.fonts.model.Font

/**
 * Map domain `Font` (+ its weight/italic faces) → the generated, contract-owned
 * `app.epistola.api.model.FontDto`.
 *
 * The DTOs are generated from the `epistola-contract` OpenAPI spec (tag
 * `Fonts`), so this mapper only converts domain → generated. `kind` maps via
 * the lowercase wire form to the generated `FontDto.Kind` enum; `catalogType`
 * maps SUBSCRIBED/AUTHORED to the generated `FontDto.CatalogType` enum.
 *
 * Fonts are read-only over REST (the asset precedent: binaries are managed
 * through the UI / catalog exchange only), so there is no `toModel` direction.
 */
internal fun Font.toDto(variants: List<FontVariantDto>) = FontDto(
    slug = slug.value,
    name = name,
    kind = FontDto.Kind.forValue(kind.wire),
    catalog = catalogKey.value,
    // `catalogType` is loaded via the JOIN in `ListFonts`, so for any row that
    // exists it's non-null. AUTHORED is the safe fallback for the orphan case.
    catalogType = when (catalogType) {
        CatalogType.SUBSCRIBED -> FontDto.CatalogType.SUBSCRIBED
        else -> FontDto.CatalogType.AUTHORED
    },
    readOnly = catalogType == CatalogType.SUBSCRIBED,
    variants = variants,
    createdAt = createdAt,
    lastModified = updatedAt,
)
