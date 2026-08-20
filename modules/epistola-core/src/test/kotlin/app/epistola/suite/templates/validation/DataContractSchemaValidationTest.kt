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

    private fun schema(json: String): ObjectNode = objectMapper.readValue(json, ObjectNode::class.java)
}
