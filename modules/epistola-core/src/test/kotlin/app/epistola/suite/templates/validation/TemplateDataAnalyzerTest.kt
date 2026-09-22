// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.validation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

class TemplateDataAnalyzerTest {

    private val objectMapper = ObjectMapper()
    private val validator = JsonSchemaValidator(objectMapper)
    private val analyzer = TemplateDataAnalyzer(validator, objectMapper)

    private fun json(text: String): ObjectNode = objectMapper.readValue(text, ObjectNode::class.java)

    private fun JsonNode.names(): List<String> = properties().map { it.key }

    private fun JsonNode.strings(): List<String> = values().map { it.asString() }

    private val customerContract = json(
        """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "type": "object",
          "properties": {
            "customer": {
              "type": "object",
              "title": "Customer",
              "properties": {
                "name": { "type": "string", "title": "Name" },
                "age": { "type": "integer", "minimum": 0 },
                "phone": { "type": "string" },
                "email": { "type": "string", "format": "email" }
              },
              "required": ["name"]
            },
            "orders": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "price": { "type": "number" },
                  "note": { "type": "string" }
                },
                "required": ["price"]
              }
            },
            "invoiceDate": { "type": "string", "format": "date" }
          },
          "required": ["customer", "invoiceDate"]
        }
        """,
    )

    @Test
    fun `complete data is valid and reports nothing`() {
        val analysis = analyzer.analyze(
            customerContract,
            json("""{"customer": {"name": "Ada"}, "invoiceDate": "2026-09-22"}"""),
            emptySet(),
        )

        assertThat(analysis.valid).isTrue()
        assertThat(analysis.missingFields).isEmpty()
        assertThat(analysis.invalidFields).isEmpty()
        assertThat(analysis.missingDataSchema).isNull()
    }

    @Nested
    inner class MissingRequired {

        @Test
        fun `a missing object is reported once, at its outermost node, with its schema`() {
            val analysis = analyzer.analyze(customerContract, json("""{"invoiceDate": "2026-09-22"}"""), emptySet())

            assertThat(analysis.valid).isFalse()
            assertThat(analysis.missingFields.map { it.path }).containsExactly("/customer")
            val field = analysis.missingFields.single()
            assertThat(field.required).isTrue()
            assertThat(field.schema.get("title").asString()).isEqualTo("Customer")
            assertThat(field.schema.get("properties").has("name")).isTrue()
        }

        @Test
        fun `a missing leaf inside a present object is reported by its full pointer`() {
            val analysis = analyzer.analyze(customerContract, json("""{"customer": {}, "invoiceDate": "2026-09-22"}"""), emptySet())

            assertThat(analysis.missingFields.map { it.path }).containsExactly("/customer/name")
            assertThat(analysis.missingFields.single().schema.get("type").asString()).isEqualTo("string")
        }

        @Test
        fun `missing fields are listed in schema declaration order`() {
            val analysis = analyzer.analyze(customerContract, json("""{}"""), emptySet())

            assertThat(analysis.missingFields.map { it.path }).containsExactly("/customer", "/invoiceDate")
        }

        @Test
        fun `a required field inside an array item is reported per item`() {
            val analysis = analyzer.analyze(
                customerContract,
                json("""{"customer": {"name": "Ada"}, "invoiceDate": "2026-09-22", "orders": [{"price": 1}, {}]}"""),
                emptySet(),
            )

            assertThat(analysis.missingFields.map { it.path }).containsExactly("/orders/1/price")
            assertThat(analysis.missingFields.single().schema.get("type").asString()).isEqualTo("number")
        }

        @Test
        fun `a field filled from its default is not missing`() {
            val contract = json(
                """{"type": "object", "properties": {"greeting": {"type": "string", "default": "Hello"}}, "required": ["greeting"]}""",
            )
            val data = validator.applyDefaults(contract, json("{}"))

            val analysis = analyzer.analyze(contract, data, emptySet())

            assertThat(analysis.valid).isTrue()
            assertThat(analysis.missingFields).isEmpty()
        }
    }

    @Nested
    inner class OptionalFields {

        private val complete = """{"customer": {"name": "Ada"}, "invoiceDate": "2026-09-22"}"""

        @Test
        fun `an absent optional field the template reads is reported as not required`() {
            val analysis = analyzer.analyze(customerContract, json(complete), setOf("customer.name", "customer.phone"))

            assertThat(analysis.valid).isTrue()
            assertThat(analysis.missingFields.map { it.path to it.required }).containsExactly("/customer/phone" to false)
        }

        @Test
        fun `an absent optional field the template does not read is left out`() {
            val analysis = analyzer.analyze(customerContract, json(complete), setOf("customer.name"))

            assertThat(analysis.missingFields).isEmpty()
        }

        @Test
        fun `a loop-alias path reaches the field in every array item that lacks it`() {
            val analysis = analyzer.analyze(
                customerContract,
                json("""{"customer": {"name": "Ada"}, "invoiceDate": "2026-09-22", "orders": [{"price": 1, "note": "x"}, {"price": 2}]}"""),
                setOf("orders", "orders[*].note"),
            )

            assertThat(analysis.missingFields.map { it.path to it.required }).containsExactly("/orders/1/note" to false)
        }

        @Test
        fun `an absent optional array the template loops over is reported once`() {
            val analysis = analyzer.analyze(customerContract, json(complete), setOf("orders", "orders[*].price"))

            assertThat(analysis.missingFields.map { it.path }).containsExactly("/orders")
            assertThat(analysis.missingFields.single().schema.get("items").get("required").strings())
                .containsExactly("price")
        }

        @Test
        fun `an absent optional object is reported at its outermost node when the template reads inside it`() {
            val contract = json(
                """
                {"type": "object", "properties": {
                  "delivery": {"type": "object", "properties": {"address": {"type": "object", "properties": {"city": {"type": "string"}}}}}
                }}
                """,
            )

            val analysis = analyzer.analyze(contract, json("{}"), setOf("delivery.address.city"))

            assertThat(analysis.missingFields.map { it.path to it.required }).containsExactly("/delivery" to false)
        }
    }

    @Nested
    inner class Invalid {

        @Test
        fun `a supplied value of the wrong type is invalid, not missing`() {
            val analysis = analyzer.analyze(
                customerContract,
                json("""{"customer": {"name": "Ada", "age": "old"}, "invoiceDate": "2026-09-22"}"""),
                emptySet(),
            )

            assertThat(analysis.valid).isFalse()
            assertThat(analysis.missingFields).isEmpty()
            val field = analysis.invalidFields.single()
            assertThat(field.path).isEqualTo("/customer/age")
            assertThat(field.keyword).isEqualTo("type")
            assertThat(field.schema?.get("minimum")?.asInt()).isEqualTo(0)
        }

        @Test
        fun `missing and invalid fields are reported together`() {
            val analysis = analyzer.analyze(customerContract, json("""{"customer": {"age": -1}}"""), emptySet())

            assertThat(analysis.missingFields.map { it.path }).containsExactly("/customer/name", "/invoiceDate")
            assertThat(analysis.invalidFields.map { it.path to it.keyword }).containsExactly("/customer/age" to "minimum")
        }

        @Test
        fun `an explicit null for a required field is invalid, not missing`() {
            val analysis = analyzer.analyze(customerContract, json("""{"customer": null, "invoiceDate": "2026-09-22"}"""), emptySet())

            assertThat(analysis.missingFields).isEmpty()
            assertThat(analysis.invalidFields.map { it.path }).containsExactly("/customer")
        }
    }

    @Nested
    inner class References {

        @Test
        fun `local refs are inlined into the reported schema`() {
            val contract = json(
                """
                {"type": "object",
                 "${'$'}defs": {"address": {"type": "object", "properties": {"city": {"type": "string"}}, "required": ["city"]}},
                 "properties": {"address": {"${'$'}ref": "#/${'$'}defs/address", "title": "Delivery address"}},
                 "required": ["address"]}
                """,
            )

            val field = analyzer.analyze(contract, json("{}"), emptySet()).missingFields.single()

            assertThat(field.schema.has("\$ref")).isFalse()
            assertThat(field.schema.get("title").asString()).isEqualTo("Delivery address")
            assertThat(field.schema.get("properties").get("city").get("type").asString()).isEqualTo("string")
        }

        @Test
        fun `a missing field behind a local ref inside a present object is found`() {
            val contract = json(
                """
                {"type": "object",
                 "${'$'}defs": {"address": {"type": "object", "properties": {"city": {"type": "string"}}, "required": ["city"]}},
                 "properties": {"address": {"${'$'}ref": "#/${'$'}defs/address"}},
                 "required": ["address"]}
                """,
            )

            val analysis = analyzer.analyze(contract, json("""{"address": {}}"""), emptySet())

            assertThat(analysis.missingFields.map { it.path }).containsExactly("/address/city")
        }

        @Test
        fun `a rich-text ref keeps its canonical url`() {
            val contract = json(
                """
                {"type": "object",
                 "properties": {"intro": {"${'$'}ref": "https://epistola.app/schemas/richtext-block-v1.json"}},
                 "required": ["intro"]}
                """,
            )

            val field = analyzer.analyze(contract, json("{}"), emptySet()).missingFields.single()

            assertThat(field.schema.get("\$ref").asString()).isEqualTo("https://epistola.app/schemas/richtext-block-v1.json")
        }

        @Test
        fun `a recursive ref does not loop and stays resolvable in the missing-data schema`() {
            val contract = json(
                """
                {"type": "object",
                 "${'$'}defs": {"node": {"type": "object", "properties": {"label": {"type": "string"}, "child": {"${'$'}ref": "#/${'$'}defs/node"}}}},
                 "properties": {"tree": {"${'$'}ref": "#/${'$'}defs/node"}},
                 "required": ["tree"]}
                """,
            )

            val analysis = analyzer.analyze(contract, json("{}"), emptySet())

            val schema = analysis.missingDataSchema!!
            assertThat(schema.get("\$defs").has("node")).isTrue()
            assertThat(validator.validateSchema(objectMapper.writeValueAsString(schema))).isEqualTo(SchemaValidationResult.Valid)
        }
    }

    @Nested
    inner class MissingDataSchema {

        @Test
        fun `holds only the missing fields, with their required flags`() {
            val analysis = analyzer.analyze(
                customerContract,
                json("""{"customer": {}}"""),
                setOf("customer.name", "customer.phone"),
            )

            val schema = analysis.missingDataSchema!!
            assertThat(schema.get("\$schema").asString()).isEqualTo("http://json-schema.org/draft-07/schema#")
            assertThat(schema.get("properties").names()).containsExactly("customer", "invoiceDate")
            assertThat(schema.get("required").strings()).containsExactly("customer", "invoiceDate")
            val customer = schema.get("properties").get("customer")
            assertThat(customer.get("title").asString()).isEqualTo("Customer")
            assertThat(customer.get("properties").names()).containsExactly("name", "phone")
            assertThat(customer.get("required").strings()).containsExactly("name")
        }

        @Test
        fun `leaves out fields inside array items, which a schema cannot address`() {
            val analysis = analyzer.analyze(
                customerContract,
                json("""{"customer": {"name": "Ada"}, "orders": [{}]}"""),
                emptySet(),
            )

            assertThat(analysis.missingFields.map { it.path }).containsExactly("/orders/0/price", "/invoiceDate")
            assertThat(analysis.missingDataSchema!!.get("properties").names()).containsExactly("invoiceDate")
        }

        @Test
        fun `data built from it, merged into the original, passes the contract`() {
            val original = json("""{"customer": {"age": 30}}""")
            val analysis = analyzer.analyze(customerContract, original, emptySet())

            // What a form built from the schema would collect.
            val collected = json("""{"customer": {"name": "Ada"}, "invoiceDate": "2026-09-22"}""")
            assertThat(validator.validate(analysis.missingDataSchema!!, collected)).isEmpty()

            val merged = original.deepCopy()
            (merged.get("customer") as ObjectNode).put("name", "Ada")
            merged.put("invoiceDate", "2026-09-22")
            assertThat(analyzer.analyze(customerContract, merged, emptySet()).valid).isTrue()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["http://json-schema.org/draft-07/schema#", "https://json-schema.org/draft/2020-12/schema"])
    fun `works for draft-07 and 2020-12 contracts`(dialect: String) {
        val contract = json(
            """
            {"${'$'}schema": "$dialect", "type": "object",
             "properties": {"a": {"type": "object", "properties": {"b": {"type": "string"}}, "required": ["b"]}, "n": {"type": "number"}},
             "required": ["a"]}
            """,
        )

        val analysis = analyzer.analyze(contract, json("""{"a": {}, "n": "x"}"""), emptySet())

        assertThat(analysis.missingFields.map { it.path }).containsExactly("/a/b")
        assertThat(analysis.invalidFields.map { it.path to it.keyword }).containsExactly("/n" to "type")
    }

    @Test
    fun `a required field declared under if-then is still reported`() {
        val contract = json(
            """
            {"${'$'}schema": "https://json-schema.org/draft/2020-12/schema", "type": "object",
             "properties": {"kind": {"type": "string"}, "vat": {"type": "string"}},
             "if": {"properties": {"kind": {"const": "business"}}, "required": ["kind"]},
             "then": {"required": ["vat"]}}
            """,
        )

        val analysis = analyzer.analyze(contract, json("""{"kind": "business"}"""), emptySet())

        assertThat(analysis.valid).isFalse()
        // Under `then` the requirement is conditional, so it surfaces as an invalid `required` keyword.
        assertThat(analysis.invalidFields.map { it.path to it.keyword }).containsExactly("" to "required")
    }

    @Test
    fun `pointer tokens are escaped`() {
        assertThat(TemplateDataAnalyzer.escapePointerToken("a/b~c")).isEqualTo("a~1b~0c")
        assertThat(TemplateDataAnalyzer.parsePointer("/a~1b~0c/0")).containsExactly("a/b~c", "0")
        assertThat(TemplateDataAnalyzer.parseReferencedPath("orders[*].items[0].price"))
            .containsExactly("orders", "[*]", "items", "[*]", "price")
    }
}
