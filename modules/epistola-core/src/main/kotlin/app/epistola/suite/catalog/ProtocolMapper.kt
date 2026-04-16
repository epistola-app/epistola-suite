package app.epistola.suite.catalog

import app.epistola.suite.templates.model.DocumentStyles
import app.epistola.suite.themes.BlockStylePresets
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Centralizes conversions between the suite's internal types and the
 * catalog protocol model's `Map<String, Any?>` representations.
 *
 * Protocol resources use plain maps for JSON-like structures (dataModel,
 * documentStyles, blockStylePresets), while the suite uses typed wrappers
 * (ObjectNode, DocumentStyles, BlockStylePresets). This mapper keeps that
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

    /** Suite BlockStylePresets → Protocol Map. */
    @Suppress("UNCHECKED_CAST")
    fun blockStylePresetsToMap(presets: BlockStylePresets?): Map<String, Any?>? = presets?.let {
        objectMapper.convertValue(it, Map::class.java) as Map<String, Any?>
    }

    /** Protocol Map → Suite BlockStylePresets. */
    fun mapToBlockStylePresets(map: Map<String, Any?>?): BlockStylePresets? = map?.let { objectMapper.convertValue(it, BlockStylePresets::class.java) }
}
