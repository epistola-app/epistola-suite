package app.epistola.suite.mcp.support

import app.epistola.suite.mcp.dto.AllowedChildrenInfo
import app.epistola.suite.mcp.dto.ApplicableStyles
import app.epistola.suite.mcp.dto.ComponentExampleFragmentInfo
import app.epistola.suite.mcp.dto.ComponentExampleInfo
import app.epistola.suite.mcp.dto.ComponentTypeInfo
import app.epistola.suite.mcp.dto.InspectorFieldInfo
import app.epistola.suite.mcp.dto.InspectorOptionInfo
import app.epistola.suite.mcp.dto.SlotTemplateInfo
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode

/**
 * Loads the component registry JSON shipped by `epistola-model` and exposes
 * typed accessors. The contract registry is the canonical static vocabulary;
 * this class just deserializes the snapshot.
 *
 * The JSON ships in the model artifact at
 * `META-INF/epistola-model/component-registry.json`.
 */
@Component
@ConditionalOnProperty(name = ["epistola.mcp.enabled"], havingValue = "true", matchIfMissing = true)
class ComponentRegistryProvider(
    @Value("classpath:META-INF/epistola-model/component-registry.json")
    private val resource: Resource,
    private val objectMapper: ObjectMapper,
) {

    /** Cached after first read; the resource is immutable for the JVM lifetime. */
    val components: List<ComponentTypeInfo> by lazy { load() }

    /** Look up a component type by its discriminator (`text`, `container`, etc.). */
    fun get(type: String): ComponentTypeInfo? = components.firstOrNull { it.type == type }

    private fun load(): List<ComponentTypeInfo> {
        val root = resource.inputStream.use { objectMapper.readTree(it) }
        val components = root.get("components") as? ArrayNode
            ?: error("component-registry.json missing 'components' array")
        return components.values().map { parseComponent(it) }
    }

    private fun parseComponent(node: JsonNode): ComponentTypeInfo = ComponentTypeInfo(
        type = node.requireText("type"),
        label = node.requireText("label"),
        icon = node.optionalText("icon"),
        category = node.requireText("category"),
        hidden = node.get("hidden")?.asBoolean() ?: false,
        slots = node.get("slots")?.values()?.map { parseSlot(it) } ?: emptyList(),
        allowedChildren = parseAllowedChildren(
            node.get("allowedChildren") ?: error("missing allowedChildren on '${node.requireText("type")}'"),
        ),
        applicableStyles = parseApplicableStyles(
            node.get("applicableStyles") ?: error("missing applicableStyles"),
        ),
        inspector = node.get("inspector")?.values()?.map { parseInspectorField(it) } ?: emptyList(),
        defaultStyles = node.get("defaultStyles")?.let { jsonToMap(it) },
        defaultProps = node.get("defaultProps")?.let { jsonToMap(it) },
        maxInstancesPerDocument = node.get("maxInstancesPerDocument")?.asInt(),
        examples = node.get("examples")?.values()?.map { parseExample(it) } ?: emptyList(),
        parameters = node.get("parameters"),
    )

    private fun parseExample(node: JsonNode) = ComponentExampleInfo(
        name = node.requireText("name"),
        description = node.requireText("description"),
        fragment = parseExampleFragment(
            node.get("fragment") ?: error("example missing 'fragment' on '${node.requireText("name")}'"),
        ),
    )

    private fun parseExampleFragment(node: JsonNode) = ComponentExampleFragmentInfo(
        rootNodeId = node.requireText("rootNodeId"),
        nodes = parseObjectMap(node.get("nodes")),
        slots = parseObjectMap(node.get("slots")),
    )

    /**
     * Parses an `Object<string, Object>` JSON value into a typed map. Used for the
     * untyped `nodes` and `slots` records inside example fragments — backend
     * passes them through unchanged.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseObjectMap(node: JsonNode?): Map<String, Map<String, Any?>> {
        if (node == null || !node.isObject) return emptyMap()
        return node.propertyNames().associateWith { (jsonToValue(node.get(it)) as? Map<String, Any?>) ?: emptyMap() }
    }

    private fun parseSlot(node: JsonNode) = SlotTemplateInfo(
        name = node.requireText("name"),
        dynamic = node.get("dynamic")?.asBoolean() ?: false,
    )

    private fun parseAllowedChildren(node: JsonNode) = AllowedChildrenInfo(
        mode = node.requireText("mode"),
        types = node.get("types")?.values()?.map { it.asString() },
    )

    private fun parseApplicableStyles(node: JsonNode): ApplicableStyles = if (node.isString && node.asString() == "all") {
        ApplicableStyles.All
    } else if (node.isArray) {
        ApplicableStyles.Subset(node.values().map { it.asString() })
    } else {
        error("applicableStyles must be \"all\" or a string array, got: $node")
    }

    private fun parseInspectorField(node: JsonNode) = InspectorFieldInfo(
        key = node.requireText("key"),
        label = node.requireText("label"),
        type = node.requireText("type"),
        options = node.get("options")?.values()?.map {
            InspectorOptionInfo(
                label = it.requireText("label"),
                value = it.get("value")?.let { v -> jsonToValue(v) },
            )
        },
        defaultValue = node.get("defaultValue")?.let { jsonToValue(it) },
        units = node.get("units")?.values()?.map { it.asString() },
    )

    private fun jsonToMap(node: JsonNode): Map<String, Any?> = node.propertyNames()
        .associateWith { jsonToValue(node.get(it)) }

    private fun jsonToValue(node: JsonNode): Any? = when {
        node.isNull -> null
        node.isString -> node.asString()
        node.isBoolean -> node.asBoolean()
        node.isInt -> node.asInt()
        node.isLong -> node.asLong()
        node.isFloat || node.isDouble -> node.asDouble()
        node.isArray -> node.values().map { jsonToValue(it) }
        node.isObject -> jsonToMap(node)
        else -> node.asString()
    }

    private fun JsonNode.requireText(field: String): String = get(field)?.asString() ?: error("missing required field '$field' on $this")

    private fun JsonNode.optionalText(field: String): String? = get(field)?.asString()
}
