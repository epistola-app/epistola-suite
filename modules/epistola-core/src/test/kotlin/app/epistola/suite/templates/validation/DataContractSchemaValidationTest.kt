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

    @Test
    fun `rejects minItems and maxItems split across an allOf member`() {
        val result = validator.validateDataContractSchema(
            schema(
                """
                {"type":"object","properties":{
                  "tags":{"type":"array","items":{"type":"string"},"minItems":5,"allOf":[{"maxItems":1}]}
                }}
                """.trimIndent(),
            ),
        )

        assertThat(result).isEqualTo(
            SchemaValidationResult.Invalid("Property \"\$.tags\" has \"maxItems\" less than \"minItems\""),
        )
    }

    @Test
    fun `accepts minItems and maxItems split across an allOf member when satisfiable together`() {
        val schema = schema(
            """
            {"type":"object","properties":{
              "tags":{"type":"array","items":{"type":"string"},"minItems":1,"allOf":[{"maxItems":5}]}
            }}
            """.trimIndent(),
        )

        assertThat(validator.validateDataContractSchema(schema)).isEqualTo(SchemaValidationResult.Valid)
    }

    @Test
    fun `rejects a oneOf branch made unsatisfiable by an inherited sibling minItems`() {
        val result = validator.validateDataContractSchema(
            schema(
                """
                {"type":"object","properties":{
                  "tags":{"type":"array","items":{"type":"string"},"minItems":5,
                    "oneOf":[{"maxItems":1},{"maxItems":10}]}
                }}
                """.trimIndent(),
            ),
        )

        assertThat(result).isEqualTo(
            SchemaValidationResult.Invalid("Property \"\$.tags.oneOf\" has \"maxItems\" less than \"minItems\""),
        )
    }

    @Test
    fun `rejects minItems and maxItems split across a draft-07 tuple items entry`() {
        // Array-form (tuple) `items` is only valid syntax under draft-07 — the 2020-12 default
        // dialect replaced it with `prefixItems` and rejects it before this check ever runs.
        val result = validator.validateDataContractSchema(
            schema(
                """
                {"${'$'}schema":"http://json-schema.org/draft-07/schema#","type":"object","properties":{
                  "row":{"type":"array","items":[
                    {"type":"array","items":{"type":"string"},"minItems":5,"maxItems":1}
                  ]}
                }}
                """.trimIndent(),
            ),
        )

        assertThat(result).isEqualTo(
            SchemaValidationResult.Invalid("Property \"\$.row.items[0]\" has \"maxItems\" less than \"minItems\""),
        )
    }

    @Test
    fun `rejects a negative minItems`() {
        val result = validator.validateDataContractSchema(
            schema("""{"type":"object","properties":{"tags":{"type":"array","items":{"type":"string"},"minItems":-1}}}"""),
        )

        assertThat(result).isEqualTo(SchemaValidationResult.Invalid("Property \"\$.tags\" has a negative \"minItems\""))
    }

    @Test
    fun `rejects a negative maxItems`() {
        val result = validator.validateDataContractSchema(
            schema("""{"type":"object","properties":{"tags":{"type":"array","items":{"type":"string"},"maxItems":-1}}}"""),
        )

        assertThat(result).isEqualTo(SchemaValidationResult.Invalid("Property \"\$.tags\" has a negative \"maxItems\""))
    }

    @Test
    fun `finds a negative minItems nested inside array items`() {
        val result = validator.validateDataContractSchema(
            schema(
                """
                {"type":"object","properties":{
                  "orders":{"type":"array","items":{"type":"object","properties":{
                    "lines":{"type":"array","items":{"type":"string"},"minItems":-2}
                  }}}
                }}
                """.trimIndent(),
            ),
        )

        assertThat(result).isEqualTo(
            SchemaValidationResult.Invalid("Property \"\$.orders.items.lines\" has a negative \"minItems\""),
        )
    }

    @Test
    fun `accepts a default value matching its own schema`() {
        val schema = schema("""{"type":"object","properties":{"age":{"type":"integer","default":30}}}""")

        assertThat(validator.validateDataContractSchema(schema)).isEqualTo(SchemaValidationResult.Valid)
    }

    @Test
    fun `accepts a false boolean default`() {
        val schema = schema("""{"type":"object","properties":{"active":{"type":"boolean","default":false}}}""")

        assertThat(validator.validateDataContractSchema(schema)).isEqualTo(SchemaValidationResult.Valid)
    }

    @Test
    fun `rejects a default value of the wrong type`() {
        val result = validator.validateDataContractSchema(
            schema("""{"type":"object","properties":{"age":{"type":"integer","default":"not-a-number"}}}"""),
        )

        assertThat(result).isEqualTo(
            SchemaValidationResult.Invalid(
                "Property \"\$.age\" has an invalid \"default\" value: string found, integer expected",
            ),
        )
    }

    @Test
    fun `rejects a default value outside its minimum`() {
        val result = validator.validateDataContractSchema(
            schema(
                """
                {"type":"object","properties":{
                  "age":{"type":"integer","minimum":18,"maximum":65,"default":10}
                }}
                """.trimIndent(),
            ),
        )

        assertThat(result).isEqualTo(
            SchemaValidationResult.Invalid(
                "Property \"\$.age\" has an invalid \"default\" value: must have a minimum value of 18",
            ),
        )
    }

    @Test
    fun `rejects a default value that does not match the relaxed date-time pattern`() {
        val result = validator.validateDataContractSchema(
            schema(
                """
                {"type":"object","properties":{
                  "start":{"type":"string","format":"date-time","default":"not-a-datetime"}
                }}
                """.trimIndent(),
            ),
        )

        assertThat(result).isInstanceOf(SchemaValidationResult.Invalid::class.java)
        assertThat((result as SchemaValidationResult.Invalid).message)
            .startsWith("Property \"\$.start\" has an invalid \"default\" value:")
    }

    @Test
    fun `finds an invalid default nested inside an object property`() {
        val result = validator.validateDataContractSchema(
            schema(
                """
                {"type":"object","properties":{
                  "customer":{"type":"object","properties":{
                    "age":{"type":"integer","default":"nope"}
                  }}
                }}
                """.trimIndent(),
            ),
        )

        assertThat(result).isEqualTo(
            SchemaValidationResult.Invalid(
                "Property \"\$.customer.age\" has an invalid \"default\" value: string found, integer expected",
            ),
        )
    }

    @Test
    fun `finds an invalid default nested inside array items`() {
        val result = validator.validateDataContractSchema(
            schema(
                """
                {"type":"object","properties":{
                  "tags":{"type":"array","items":{"type":"string","default":42}}
                }}
                """.trimIndent(),
            ),
        )

        assertThat(result).isEqualTo(
            SchemaValidationResult.Invalid(
                "Property \"\$.tags.items\" has an invalid \"default\" value: integer found, string expected",
            ),
        )
    }

    @Test
    fun `finds an invalid default inside a oneOf branch`() {
        val result = validator.validateDataContractSchema(
            schema(
                """
                {"type":"object","properties":{
                  "value":{"oneOf":[{"type":"string","default":42},{"type":"integer"}]}
                }}
                """.trimIndent(),
            ),
        )

        assertThat(result).isEqualTo(
            SchemaValidationResult.Invalid(
                "Property \"\$.value.oneOf\" has an invalid \"default\" value: integer found, string expected",
            ),
        )
    }

    @Test
    fun `finds an invalid default inherited through an allOf member`() {
        val result = validator.validateDataContractSchema(
            schema(
                """
                {"type":"object","properties":{
                  "value":{"allOf":[{"type":"integer","minimum":18}],"default":5}
                }}
                """.trimIndent(),
            ),
        )

        assertThat(result).isEqualTo(
            SchemaValidationResult.Invalid(
                "Property \"\$.value\" has an invalid \"default\" value: must have a minimum value of 18",
            ),
        )
    }

    @Test
    fun `applyDefaults fills an omitted property from its schema default`() {
        val result = validator.applyDefaults(
            schema("""{"type":"object","properties":{"discount":{"type":"integer","default":0}}}"""),
            data("""{}"""),
        )

        assertThat(result).isEqualTo(data("""{"discount":0}"""))
    }

    @Test
    fun `applyDefaults leaves a provided property untouched`() {
        val result = validator.applyDefaults(
            schema("""{"type":"object","properties":{"discount":{"type":"integer","default":0}}}"""),
            data("""{"discount":15}"""),
        )

        assertThat(result).isEqualTo(data("""{"discount":15}"""))
    }

    @Test
    fun `applyDefaults leaves an explicit null untouched`() {
        val result = validator.applyDefaults(
            schema("""{"type":"object","properties":{"discount":{"type":"integer","default":0}}}"""),
            data("""{"discount":null}"""),
        )

        assertThat(result).isEqualTo(data("""{"discount":null}"""))
    }

    @Test
    fun `applyDefaults recurses into a nested object property`() {
        val result = validator.applyDefaults(
            schema(
                """
                {"type":"object","properties":{
                  "address":{"type":"object","properties":{"country":{"type":"string","default":"NL"}}}
                }}
                """.trimIndent(),
            ),
            data("""{"address":{}}"""),
        )

        assertThat(result).isEqualTo(data("""{"address":{"country":"NL"}}"""))
    }

    private fun schema(json: String): ObjectNode = objectMapper.readValue(json, ObjectNode::class.java)

    private fun data(json: String): ObjectNode = objectMapper.readValue(json, ObjectNode::class.java)
}
