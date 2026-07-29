// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.api.model.ThemeDto
import app.epistola.suite.themes.BlockStylePresets
import app.epistola.suite.themes.Theme
import app.epistola.template.model.BlockStylePreset

internal fun Theme.toDto() = ThemeDto(
    id = id.value,
    tenantId = tenantKey.value,
    name = name,
    description = description,
    documentStyles = documentStyles,
    pageSettings = pageSettings,
    blockStylePresets = blockStylePresets,
    spacingUnit = spacingUnit?.toBigDecimal(),
    createdAt = createdAt,
    lastModified = updatedAt,
)

/** Wraps portable presets only where Suite persistence requires its map wrapper. */
internal fun Map<String, BlockStylePreset>?.toDomainPresets(): BlockStylePresets? = this?.let(::BlockStylePresets)
