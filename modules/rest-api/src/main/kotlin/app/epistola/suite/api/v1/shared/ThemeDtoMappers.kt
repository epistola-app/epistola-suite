// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.api.model.ThemeDto
import app.epistola.suite.themes.BlockStylePresets
import app.epistola.suite.themes.Theme
import app.epistola.template.model.BlockStylePreset

internal fun Theme.toDto() = ThemeDto(
    key = id.value,
    tenantKey = tenantKey.value,
    name = name,
    description = description,
    documentStyles = documentStyles,
    pageSettings = pageSettings,
    blockStylePresets = blockStylePresets,
    spacingUnit = spacingUnit?.toBigDecimal(),
    defaultBlockStyles = defaultBlockStyles?.let { BLOCK_STYLES_MAPPER.valueToTree<tools.jackson.databind.node.ObjectNode>(it) },
    createdAt = createdAt,
    lastModified = updatedAt,
)

/** Wraps portable presets only where Suite persistence requires its map wrapper. */
internal fun Map<String, BlockStylePreset>?.toDomainPresets(): BlockStylePresets? = this?.let(::BlockStylePresets)

/** The theme's baseline block margins, as the generated DTOs carry them (a JSON object). */
internal fun tools.jackson.databind.node.ObjectNode?.toBlockStyles(): Map<String, Any?>? = this?.takeIf { !it.isEmpty }?.let { BLOCK_STYLES_MAPPER.treeToValue(it, Map::class.java) }
    ?.let {
        @Suppress("UNCHECKED_CAST")
        it as Map<String, Any?>
    }

private val BLOCK_STYLES_MAPPER = tools.jackson.databind.json.JsonMapper.builder().build()
