package app.epistola.suite.templates.validation

import app.epistola.suite.templates.model.DataExample
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Validates JSON data against JSON Schema definitions.
 * Uses networknt/json-schema-validator 3.0.0 with JSON Schema 2020-12 specification.
 */
@Component
class JsonSchemaValidator(
    private val objectMapper: ObjectMapper,
) {
    private val schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)

    /**
     * Validates that a JSON string is a valid JSON Schema.
     *
     * @param schemaJson The JSON Schema as a string
     * @return ValidationResult containing either success or error message
     */
    fun validateSchema(schemaJson: String): SchemaValidationResult {
        // First check if it's valid JSON
        try {
            objectMapper.readTree(schemaJson)
        } catch (e: Exception) {
            return SchemaValidationResult.Invalid("Invalid JSON: the input is not valid JSON syntax")
        }

        // Then try to parse it as a JSON Schema
        return try {
            schemaRegistry.getSchema(schemaJson)
            SchemaValidationResult.Valid
        } catch (e: Exception) {
            SchemaValidationResult.Invalid("Invalid JSON Schema: ${e.message}")
        }
    }

    /**
     * Validates data against a JSON Schema.
     *
     * @param schema The JSON Schema as an ObjectNode
     * @param data The data to validate as an ObjectNode
     * @return List of validation errors, empty if valid
     */
    fun validate(schema: ObjectNode, data: ObjectNode): List<ValidationError> {
        val schemaJson = objectMapper.writeValueAsString(schema)
        val dataJson = objectMapper.writeValueAsString(data)

        val jsonSchema = schemaRegistry.getSchema(schemaJson)
        val errors = jsonSchema.validate(dataJson, InputFormat.JSON)

        return errors.map { error -> ValidationError(error.message, error.instanceLocation.toString()) }
    }

    /**
     * Validates all data examples against a JSON Schema.
     *
     * @param schema The JSON Schema as an ObjectNode
     * @param examples The list of named data examples to validate
     * @return Map of example name to list of validation errors (only includes examples with errors)
     */
    fun validateExamples(
        schema: ObjectNode,
        examples: List<DataExample>,
    ): Map<String, List<ValidationError>> = examples
        .associate { example -> example.name to validate(schema, example.data) }
        .filterValues { errors -> errors.isNotEmpty() }

    /**
     * Analyzes schema compatibility with existing data examples.
     * Returns migration suggestions for incompatible examples instead of just errors.
     *
     * @param schema The new JSON Schema to validate against
     * @param examples The list of existing data examples
     * @return SchemaCompatibilityResult with compatibility status and migration suggestions
     */
    fun analyzeCompatibility(
        schema: ObjectNode,
        examples: List<DataExample>,
    ): SchemaCompatibilityResult {
        if (examples.isEmpty()) {
            return SchemaCompatibilityResult.compatible()
        }

        val migrations = mutableListOf<MigrationSuggestion>()
        val errors = mutableListOf<ValidationError>()

        for (example in examples) {
            val validationErrors = validate(schema, example.data)

            for (error in validationErrors) {
                val migration = analyzeMigration(example, error, schema)
                if (migration != null) {
                    migrations.add(migration)
                } else {
                    errors.add(error)
                }
            }
        }

        return SchemaCompatibilityResult(
            compatible = migrations.isEmpty() && errors.isEmpty(),
            errors = errors,
            migrations = migrations,
        )
    }

    /**
     * Analyzes a validation error and determines if it can be auto-migrated.
     *
     * @param example The data example with the error
     * @param error The validation error to analyze
     * @param schema The target schema
     * @return MigrationSuggestion if migration is possible, null otherwise
     */
    private fun analyzeMigration(
        example: DataExample,
        error: ValidationError,
        schema: ObjectNode,
    ): MigrationSuggestion? {
        val issueType = detectIssueType(error)
        val currentValue = getValueAtPath(example.data, error.path)
        val expectedType = getExpectedType(schema, error.path)

        return when (issueType) {
            ValidationIssueType.TYPE_MISMATCH -> {
                val (suggestedValue, autoMigratable) = tryConvertValue(currentValue, expectedType)
                MigrationSuggestion(
                    exampleId = example.id,
                    exampleName = example.name,
                    path = error.path,
                    issue = issueType,
                    currentValue = currentValue,
                    expectedType = expectedType,
                    suggestedValue = suggestedValue,
                    autoMigratable = autoMigratable,
                )
            }
            ValidationIssueType.MISSING_REQUIRED -> {
                MigrationSuggestion(
                    exampleId = example.id,
                    exampleName = example.name,
                    path = error.path,
                    issue = issueType,
                    currentValue = null,
                    expectedType = expectedType,
                    suggestedValue = null,
                    autoMigratable = false,
                )
            }
            ValidationIssueType.UNKNOWN_FIELD -> {
                null // Unknown fields don't need migration suggestions
            }
        }
    }

    /**
     * Detects the type of validation issue from an error message.
     */
    private fun detectIssueType(error: ValidationError): ValidationIssueType {
        val message = error.message.lowercase()
        return when {
            message.contains("type") -> ValidationIssueType.TYPE_MISMATCH
            message.contains("required") -> ValidationIssueType.MISSING_REQUIRED
            message.contains("additional") || message.contains("unrecognized") ->
                ValidationIssueType.UNKNOWN_FIELD
            else -> ValidationIssueType.TYPE_MISMATCH // Default to type mismatch
        }
    }

    /**
     * Gets the value at a JSON path in the data.
     */
    private fun getValueAtPath(data: ObjectNode, path: String): JsonNode? {
        if (path.isEmpty() || path == "$") {
            return data
        }

        // Parse JSON Pointer path (e.g., "$.field" or "/field")
        val segments = path
            .removePrefix("$.")
            .removePrefix("$")
            .removePrefix("/")
            .split(".", "/")
            .filter { it.isNotEmpty() }

        var current: JsonNode = data
        for (segment in segments) {
            current = when {
                current.isObject -> current.get(segment) ?: return null
                current.isArray -> {
                    val index = segment.toIntOrNull() ?: return null
                    current.get(index) ?: return null
                }
                else -> return null
            }
        }
        return current
    }

    /**
     * Gets the expected type from the schema at a given path.
     */
    private fun getExpectedType(schema: ObjectNode, path: String): String {
        val segments = path
            .removePrefix("$.")
            .removePrefix("$")
            .removePrefix("/")
            .split(".", "/")
            .filter { it.isNotEmpty() }

        var current: JsonNode = schema
        for (segment in segments) {
            // Navigate through properties
            val properties = current.get("properties")
            if (properties != null && properties.has(segment)) {
                current = properties.get(segment)
            } else if (current.get("items") != null) {
                // Array item type
                current = current.get("items")
                val props = current.get("properties")
                if (props != null && props.has(segment)) {
                    current = props.get(segment)
                }
            }
        }

        val typeNode = current.get("type")
        return typeNode?.asString() ?: "unknown"
    }

    /**
     * Attempts to convert a value to the expected type.
     *
     * @return Pair of (suggested value, is auto-migratable)
     */
    private fun tryConvertValue(currentValue: JsonNode?, expectedType: String): Pair<JsonNode?, Boolean> {
        if (currentValue == null) {
            return Pair(null, false)
        }

        return when (expectedType) {
            "string" -> tryConvertToString(currentValue)
            "number", "integer" -> tryConvertToNumber(currentValue, expectedType)
            "boolean" -> tryConvertToBoolean(currentValue)
            else -> Pair(null, false)
        }
    }

    private fun tryConvertToString(value: JsonNode): Pair<JsonNode?, Boolean> = when {
        value.isString -> Pair(value, true)
        value.isNumber || value.isBoolean -> {
            val stringValue = objectMapper.valueToTree<JsonNode>(value.asString())
            Pair(stringValue, true)
        }
        else -> Pair(null, false) // Objects/arrays cannot be auto-converted to string
    }

    private fun tryConvertToNumber(value: JsonNode, expectedType: String): Pair<JsonNode?, Boolean> = when {
        value.isNumber -> Pair(value, true)
        value.isString -> {
            val text = value.asString()
            val number = if (expectedType == "integer") {
                text.toLongOrNull()?.let { objectMapper.valueToTree<JsonNode>(it) }
            } else {
                text.toDoubleOrNull()?.let { objectMapper.valueToTree<JsonNode>(it) }
            }
            if (number != null) Pair(number, true) else Pair(null, false)
        }
        else -> Pair(null, false)
    }

    private fun tryConvertToBoolean(value: JsonNode): Pair<JsonNode?, Boolean> = when {
        value.isBoolean -> Pair(value, true)
        value.isString -> {
            val text = value.asString().lowercase()
            when (text) {
                "true", "1", "yes" -> Pair(objectMapper.valueToTree(true), true)
                "false", "0", "no" -> Pair(objectMapper.valueToTree(false), true)
                else -> Pair(null, false)
            }
        }
        value.isNumber -> {
            val boolValue = value.asInt() != 0
            Pair(objectMapper.valueToTree(boolValue), true)
        }
        else -> Pair(null, false)
    }
}

/**
 * Result of validating a JSON Schema definition.
 */
sealed class SchemaValidationResult {
    data object Valid : SchemaValidationResult()
    data class Invalid(val message: String) : SchemaValidationResult()
}
