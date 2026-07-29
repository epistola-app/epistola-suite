// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.templates.model.DocumentStyles
import app.epistola.suite.themes.BlockStylePresets
import app.epistola.template.model.BlockStylePreset
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Centralizes conversions between the suite's internal types and the
 * catalog protocol representations.
 *
 * Protocol resources use plain maps for JSON-like structures such as data models
 * and document styles, while the suite uses typed wrappers for persistence. This mapper keeps that
 * translation in one place so import/export handlers stay focused on
 * orchestration logic.
 */
@Component
class ProtocolMapper(private val objectMapper: ObjectMapper) {

    /** Suite ObjectNode → Protocol Map. */
    @Suppress("UNCHECKED_CAST")
    fun toMap(node: ObjectNode?): Map<String, Any?>? = node?.let {
        objectMapper.treeToValue(it, Map::class.java) as Map<String, Any?>
    }

    /** Protocol Map → Suite ObjectNode. */
    fun toObjectNode(map: Map<String, Any?>?): ObjectNode? = map?.let { objectMapper.valueToTree(it) }

    /** Suite DocumentStyles → Protocol Map. */
    fun documentStylesToMap(styles: DocumentStyles?): Map<String, Any?>? = styles?.mapValues { (_, v) -> v as Any? }

    /** Protocol Map → Suite DocumentStyles. */
    fun mapToDocumentStyles(map: Map<String, Any?>?): DocumentStyles = map?.filterValues { it != null }?.mapValues { (_, v) -> v!! } ?: emptyMap()

    /** Suite persistence wrapper → portable catalog preset map. */
    fun blockStylePresetsToMap(presets: BlockStylePresets?): Map<String, BlockStylePreset>? = presets

    /** Portable catalog preset map → Suite persistence wrapper. */
    fun mapToBlockStylePresets(map: Map<String, BlockStylePreset>?): BlockStylePresets? = map?.let(::BlockStylePresets)
}
