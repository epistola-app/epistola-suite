package app.epistola.suite.stencils.model

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * Typed view of a single parameter declared in a stencil version's parameter schema.
 *
 * The canonical storage is JSON Schema on [StencilVersion.parameterSchema]; this is a
 * derived ergonomic view, never persisted on its own. V1 surfaces only the primitive
 * subset users actually need; the schema can hold richer shapes in the future without
 * changes here being load-bearing.
 */
data class StencilParameter(
    val name: String,
    val type: StencilParameterType,
    val isList: Boolean,
    val required: Boolean,
    val description: String?,
    val default: JsonNode?,
)

/**
 * Primitive parameter types exposed in the v1 author UI. Each maps to a JSON Schema
 * shape under the hood — see [parametersFromSchema] for the encoding.
 */
enum class StencilParameterType {
    STRING,
    NUMBER,
    INTEGER,
    BOOLEAN,
    DATE,
    DATE_TIME,
    ;

    companion object {
        fun fromSchema(typeNode: JsonNode?, formatNode: JsonNode?): StencilParameterType? = when (typeNode?.asString()) {
            "string" -> when (formatNode?.asString()) {
                "date" -> DATE
                "date-time" -> DATE_TIME
                null -> STRING
                else -> null
            }
            "number" -> NUMBER
            "integer" -> INTEGER
            "boolean" -> BOOLEAN
            else -> null
        }
    }
}

/**
 * Decodes a JSON Schema parameter declaration into a list of typed [StencilParameter]
 * views, in declaration order. Returns an empty list if the schema is null or has no
 * properties. Skips properties whose type is not v1-supported (a [ParameterSchemaValidator]
 * upstream would have rejected them, so this only happens for malformed legacy data).
 */
fun parametersFromSchema(schema: JsonNode?): List<StencilParameter> {
    if (schema == null || schema.isNull || schema !is ObjectNode) return emptyList()
    val properties = schema.get("properties") as? ObjectNode ?: return emptyList()
    val required = (schema.get("required") as? ArrayNode)?.mapNotNull { it.asString() }?.toSet() ?: emptySet()

    return properties.properties().mapNotNull { (name, prop) ->
        if (prop !is ObjectNode) return@mapNotNull null
        val typeStr = prop.get("type")?.asString()
        when (typeStr) {
            "array" -> {
                val items = prop.get("items") as? ObjectNode ?: return@mapNotNull null
                val itemType = StencilParameterType.fromSchema(items.get("type"), items.get("format")) ?: return@mapNotNull null
                StencilParameter(
                    name = name,
                    type = itemType,
                    isList = true,
                    required = name in required,
                    description = prop.get("description")?.asString(),
                    default = prop.get("default"),
                )
            }
            else -> {
                val type = StencilParameterType.fromSchema(prop.get("type"), prop.get("format")) ?: return@mapNotNull null
                StencilParameter(
                    name = name,
                    type = type,
                    isList = false,
                    required = name in required,
                    description = prop.get("description")?.asString(),
                    default = prop.get("default"),
                )
            }
        }
    }
}
