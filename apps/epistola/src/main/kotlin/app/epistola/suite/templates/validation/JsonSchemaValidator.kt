package app.epistola.suite.templates.validation

import app.epistola.suite.templates.model.DataExample
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.springframework.stereotype.Component
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
}
