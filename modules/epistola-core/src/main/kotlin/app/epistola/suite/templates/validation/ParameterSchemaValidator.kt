package app.epistola.suite.templates.validation

import app.epistola.suite.validation.ValidationException
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * Validates a JSON Schema declared as a node's parameter schema (today: stencil
 * versions; future: any parametrised component). Component-agnostic by design:
 * it only inspects the schema shape, never where the schema came from.
 *
 * V1 supports the primitive subset users actually need:
 *   - string (with optional format: "date" | "date-time")
 *   - number / integer
 *   - boolean
 *   - array of one of the above primitives
 *
 * Anything else (nested objects, oneOf/anyOf, enums, ...) is rejected so the v1
 * surface stays small. The storage shape stays canonical JSON Schema, so a
 * future v2 can lift these restrictions without a migration.
 */
@Component
class ParameterSchemaValidator {

    private val nameRegex = Regex("^[a-z][a-zA-Z0-9_]{0,63}$")
    private val reservedNames = setOf("params", "item", "sys", "index")
    private val reservedSuffixes = listOf("_index", "_first", "_last")
    private val supportedPrimitives = setOf("string", "number", "integer", "boolean")
    private val supportedStringFormats = setOf("date", "date-time")

    /**
     * Validates the given schema. NULL is a valid no-op (means "no parameters").
     * Throws [ValidationException] on the first violation.
     */
    fun validate(schema: JsonNode?, fieldPrefix: String = "parameterSchema") {
        if (schema == null || schema.isNull) return
        if (schema !is ObjectNode) {
            throw ValidationException(
                fieldPrefix,
                "PARAMETER_SCHEMA_INVALID_TYPE: parameter schema must be a JSON object",
            )
        }
        val type = schema.get("type")?.asString()
        if (type != "object") {
            throw ValidationException(
                "$fieldPrefix.type",
                "PARAMETER_SCHEMA_INVALID_TYPE: parameter schema 'type' must be 'object'; got '${type ?: "<missing>"}'",
            )
        }
        val properties = schema.get("properties")
        if (properties != null && !properties.isNull && properties !is ObjectNode) {
            throw ValidationException(
                "$fieldPrefix.properties",
                "PARAMETER_SCHEMA_INVALID_TYPE: 'properties' must be an object",
            )
        }
        val propertiesObj = properties as? ObjectNode
        val declaredNames = mutableSetOf<String>()
        propertiesObj?.propertyNames()?.forEach { name -> declaredNames.add(name) }

        propertiesObj?.properties()?.forEach { (name, propSchema) ->
            validateName(name, fieldPrefix)
            validateProperty(name, propSchema, "$fieldPrefix.properties.$name")
        }

        val required = schema.get("required")
        if (required != null && !required.isNull) {
            if (required !is ArrayNode) {
                throw ValidationException(
                    "$fieldPrefix.required",
                    "PARAMETER_SCHEMA_INVALID_TYPE: 'required' must be an array",
                )
            }
            for (item in required) {
                val req = item.asString()
                if (req !in declaredNames) {
                    throw ValidationException(
                        "$fieldPrefix.required",
                        "PARAMETER_REQUIRED_UNKNOWN: required parameter '$req' is not declared in 'properties'",
                    )
                }
            }
        }
    }

    private fun validateName(name: String, fieldPrefix: String) {
        if (!nameRegex.matches(name)) {
            throw ValidationException(
                "$fieldPrefix.properties",
                "PARAMETER_NAME_INVALID: parameter name '$name' must match ^[a-z][a-zA-Z0-9_]{0,63}\$",
            )
        }
        if (name in reservedNames || reservedSuffixes.any { name.endsWith(it) }) {
            throw ValidationException(
                "$fieldPrefix.properties",
                "PARAMETER_NAME_RESERVED: parameter name '$name' collides with a reserved scope name",
            )
        }
    }

    private fun validateProperty(name: String, propSchema: JsonNode, fieldPath: String) {
        if (propSchema !is ObjectNode) {
            throw ValidationException(
                fieldPath,
                "PARAMETER_TYPE_UNSUPPORTED: parameter '$name' must be a schema object",
            )
        }
        val type = propSchema.get("type")?.asString()
            ?: throw ValidationException(
                "$fieldPath.type",
                "PARAMETER_TYPE_UNSUPPORTED: parameter '$name' is missing 'type'",
            )

        when (type) {
            in supportedPrimitives -> validatePrimitive(name, propSchema, type, fieldPath)
            "array" -> validateArray(name, propSchema, fieldPath)
            else -> throw ValidationException(
                "$fieldPath.type",
                "PARAMETER_TYPE_UNSUPPORTED: parameter '$name' has unsupported type '$type' " +
                    "(v1 supports: string, number, integer, boolean, array of those)",
            )
        }
    }

    private fun validatePrimitive(name: String, propSchema: ObjectNode, type: String, fieldPath: String) {
        if (type == "string") {
            val format = propSchema.get("format")?.asString()
            if (format != null && format !in supportedStringFormats) {
                throw ValidationException(
                    "$fieldPath.format",
                    "PARAMETER_TYPE_UNSUPPORTED: parameter '$name' has unsupported string format '$format' " +
                        "(v1 supports: date, date-time)",
                )
            }
        }
        validateDefaultMatchesPrimitive(name, propSchema, type, fieldPath)
    }

    private fun validateArray(name: String, propSchema: ObjectNode, fieldPath: String) {
        val items = propSchema.get("items")
            ?: throw ValidationException(
                "$fieldPath.items",
                "PARAMETER_TYPE_UNSUPPORTED: array parameter '$name' is missing 'items'",
            )
        if (items !is ObjectNode) {
            throw ValidationException(
                "$fieldPath.items",
                "PARAMETER_TYPE_UNSUPPORTED: array parameter '$name' 'items' must be a schema object",
            )
        }
        val itemType = items.get("type")?.asString()
        if (itemType !in supportedPrimitives) {
            throw ValidationException(
                "$fieldPath.items.type",
                "PARAMETER_TYPE_UNSUPPORTED: array parameter '$name' must contain primitives; got '${itemType ?: "<missing>"}'",
            )
        }
        if (itemType == "string") {
            val format = items.get("format")?.asString()
            if (format != null && format !in supportedStringFormats) {
                throw ValidationException(
                    "$fieldPath.items.format",
                    "PARAMETER_TYPE_UNSUPPORTED: array parameter '$name' items have unsupported string format '$format'",
                )
            }
        }
        validateDefaultMatchesArray(name, propSchema, itemType, fieldPath)
    }

    private fun validateDefaultMatchesPrimitive(name: String, propSchema: ObjectNode, type: String, fieldPath: String) {
        val def = propSchema.get("default") ?: return
        val ok = when (type) {
            "string" -> def.isString
            "number" -> def.isNumber
            "integer" -> def.isIntegralNumber
            "boolean" -> def.isBoolean
            else -> false
        }
        if (!ok) {
            throw ValidationException(
                "$fieldPath.default",
                "PARAMETER_DEFAULT_TYPE_MISMATCH: parameter '$name' default does not match declared type '$type'",
            )
        }
    }

    private fun validateDefaultMatchesArray(name: String, propSchema: ObjectNode, itemType: String?, fieldPath: String) {
        val def = propSchema.get("default") ?: return
        if (def !is ArrayNode) {
            throw ValidationException(
                "$fieldPath.default",
                "PARAMETER_DEFAULT_TYPE_MISMATCH: parameter '$name' default must be an array",
            )
        }
        if (itemType == null) return
        for (item in def) {
            val ok = when (itemType) {
                "string" -> item.isString
                "number" -> item.isNumber
                "integer" -> item.isIntegralNumber
                "boolean" -> item.isBoolean
                else -> false
            }
            if (!ok) {
                throw ValidationException(
                    "$fieldPath.default",
                    "PARAMETER_DEFAULT_TYPE_MISMATCH: parameter '$name' default items must be of type '$itemType'",
                )
            }
        }
    }
}
