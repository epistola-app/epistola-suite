// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.validation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

class DataContractSchemaValidationTest {
    private val objectMapper = ObjectMapper()
    private val validator = JsonSchemaValidator(objectMapper)

    @Test
    fun `accepts an object data contract schema`() {
        assertThat(validator.validateDataContractSchema(schema("""{"type":"object","properties":{}}""")))
            .isEqualTo(SchemaValidationResult.Valid)
    }

    @Test
    fun `accepts an object root through a local reference`() {
        val schema = schema(
            """
            {
              "${'$'}ref": "#/${'$'}defs/root",
              "${'$'}defs": {"root": {"type": "object", "properties": {}}}
            }
            """.trimIndent(),
        )

        assertThat(validator.validateDataContractSchema(schema)).isEqualTo(SchemaValidationResult.Valid)
    }

    @Test
    fun `accepts an object root constrained by composition`() {
        val schema = schema(
            """
            {
              "allOf": [
                {"${'$'}ref": "#/${'$'}defs/root"},
                {"additionalProperties": false}
              ],
              "${'$'}defs": {"root": {"type": "object", "properties": {}}}
            }
            """.trimIndent(),
        )

        assertThat(validator.validateDataContractSchema(schema)).isEqualTo(SchemaValidationResult.Valid)
    }

    @Test
    fun `rejects an arbitrary JSON object`() {
        val result = validator.validateDataContractSchema(
            schema("""{"schemaVersion":5,"resource":{"type":"template"}}"""),
        )

        assertThat(result).isEqualTo(
            SchemaValidationResult.Invalid("A data contract JSON Schema must require an object at its root"),
        )
    }

    @Test
    fun `rejects a schema with a non-object root branch`() {
        val result = validator.validateDataContractSchema(
            schema("""{"oneOf":[{"type":"object"},{"type":"string"}]}"""),
        )

        assertThat(result).isInstanceOf(SchemaValidationResult.Invalid::class.java)
    }

    @Test
    fun `accepts an array property whose maxItems is not less than minItems`() {
        val schema = schema(
            """
            {"type":"object","properties":{
              "tags":{"type":"array","items":{"type":"string"},"minItems":1,"maxItems":5}
            }}
            """.trimIndent(),
        )

        assertThat(validator.validateDataContractSchema(schema)).isEqualTo(SchemaValidationResult.Valid)
    }

    @Test
    fun `rejects an array property whose maxItems is less than minItems`() {
        val result = validator.validateDataContractSchema(
            schema(
                """
                {"type":"object","properties":{
                  "tags":{"type":"array","items":{"type":"string"},"minItems":5,"maxItems":1}
                }}
                """.trimIndent(),
            ),
        )

        assertThat(result).isEqualTo(
            SchemaValidationResult.Invalid("Property \"\$.tags\" has \"maxItems\" less than \"minItems\""),
        )
    }

    @Test
    fun `accepts an equal maxItems and minItems combination`() {
        val schema = schema(
            """
            {"type":"object","properties":{
              "tags":{"type":"array","items":{"type":"string"},"minItems":3,"maxItems":3}
            }}
            """.trimIndent(),
        )

        assertThat(validator.validateDataContractSchema(schema)).isEqualTo(SchemaValidationResult.Valid)
    }

    @Test
    fun `finds an invalid maxItems-minItems pair nested inside an object property`() {
        val result = validator.validateDataContractSchema(
            schema(
                """
                {"type":"object","properties":{
                  "customer":{"type":"object","properties":{
                    "tags":{"type":"array","items":{"type":"string"},"minItems":5,"maxItems":1}
                  }}
                }}
                """.trimIndent(),
            ),
        )

        assertThat(result).isEqualTo(
            SchemaValidationResult.Invalid(
                "Property \"\$.customer.tags\" has \"maxItems\" less than \"minItems\"",
            ),
        )
    }

    @Test
    fun `finds an invalid maxItems-minItems pair nested inside array items`() {
        val result = validator.validateDataContractSchema(
            schema(
                """
                {"type":"object","properties":{
                  "orders":{"type":"array","items":{"type":"object","properties":{
                    "lines":{"type":"array","items":{"type":"string"},"minItems":5,"maxItems":1}
                  }}}
                }}
                """.trimIndent(),
            ),
        )

        assertThat(result).isEqualTo(
            SchemaValidationResult.Invalid(
                "Property \"\$.orders.items.lines\" has \"maxItems\" less than \"minItems\"",
            ),
        )
    }

    private fun schema(json: String): ObjectNode = objectMapper.readValue(json, ObjectNode::class.java)
}
