// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.api.model.FontDto
import app.epistola.api.model.FontVariantDto
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.fonts.model.Font

/**
 * Map domain `Font` (+ its weight/italic faces) → the generated, contract-owned
 * `app.epistola.api.model.FontDto`.
 *
 * The DTOs are generated from the `epistola-contract` OpenAPI spec (tag
 * `Fonts`), so this mapper only converts domain → generated. `kind` maps via
 * the lowercase wire form to the generated `FontDto.Kind` enum; `readOnly`
 * says whether the font's catalog cannot be changed here.
 *
 * Fonts are read-only over REST (the asset precedent: binaries are managed
 * through the UI / catalog exchange only), so there is no `toModel` direction.
 */
internal fun Font.toDto(catalog: Catalog, variants: List<FontVariantDto>, readOnly: Boolean = catalog.readOnlyHere()) = FontDto(
    key = slug.value,
    name = name,
    kind = FontDto.Kind.forValue(kind.wire),
    registry = catalog.registry,
    namespace = catalog.namespace,
    catalogKey = catalogKey.value,
    readOnly = readOnly,
    variants = variants,
    createdAt = createdAt,
    lastModified = updatedAt,
)
