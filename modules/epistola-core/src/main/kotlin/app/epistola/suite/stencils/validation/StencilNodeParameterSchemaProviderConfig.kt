package app.epistola.suite.stencils.validation

import app.epistola.suite.stencils.StencilNodeKeys
import app.epistola.suite.templates.validation.NodeParameterSchemaProvider
import app.epistola.suite.templates.validation.NodeTypeProviderBinding
import app.epistola.template.model.Node
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Schema provider for stencil nodes — reads the snapshot copied onto the node's
 * props at insert/upgrade time. Stencils are dynamic components: their parameter
 * schema is per-version, authored by users, and lives on `StencilVersion.parameter_schema`
 * in the database. Snapshotting onto each consuming node keeps render-time and
 * validation paths free of DB lookups; the snapshot is refreshed by
 * [app.epistola.suite.stencils.model.StencilContentReplacer] when a stencil is
 * upgraded.
 *
 * Returns null when the node has no snapshot (legacy stencils predating the
 * parameters feature, or stencils whose authors haven't declared any parameters).
 * Callers treat null and empty-schema identically.
 *
 * The snapshot in props may be a `Map<String, Any?>` (Jackson-deserialized JSON
 * content from JSONB) or a `JsonNode` (when set directly via Kotlin code). The
 * provider normalises to `Map<String, Any?>` so consumers (including the render
 * pipeline, which has no Jackson dependency) see a canonical shape.
 */
@Configuration
class StencilNodeParameterSchemaProviderConfig(
    private val objectMapper: ObjectMapper,
) {
    @Bean
    fun stencilNodeParameterSchemaProvider(): NodeTypeProviderBinding = NodeTypeProviderBinding(
        nodeType = StencilNodeKeys.NODE_TYPE,
        provider = NodeParameterSchemaProvider { node, _ -> readSnapshot(node) },
    )

    @Suppress("UNCHECKED_CAST")
    private fun readSnapshot(node: Node): Map<String, Any?>? {
        val raw = node.props?.get(StencilNodeKeys.PROP_PARAMETER_SCHEMA_SNAPSHOT) ?: return null
        return when (raw) {
            is Map<*, *> -> raw as Map<String, Any?>
            is JsonNode -> objectMapper.convertValue(raw, Map::class.java) as Map<String, Any?>
            else -> null
        }
    }
}
