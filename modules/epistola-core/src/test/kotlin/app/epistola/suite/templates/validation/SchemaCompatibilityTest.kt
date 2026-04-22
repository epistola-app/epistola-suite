package app.epistola.suite.templates.validation

import app.epistola.suite.templates.model.DataExample
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

class SchemaCompatibilityTest {

    private lateinit var objectMapper: ObjectMapper
    private lateinit var validator: JsonSchemaValidator

    @BeforeEach
    fun setUp() {
        objectMapper = ObjectMapper()
        validator = JsonSchemaValidator(objectMapper)
    }

    private fun createSchema(json: String): ObjectNode = objectMapper.readValue(json, ObjectNode::class.java)

    private fun createExample(id: String, name: String, json: String): DataExample = DataExample(id, name, objectMapper.readValue(json, ObjectNode::class.java))

    @Nested
    inner class AnalyzeCompatibilityTest {

        @Test
        fun `returns compatible when no examples exist`() {
            val schema = createSchema("""{"type": "object", "properties": {"name": {"type": "string"}}}""")

            val result = validator.analyzeCompatibility(schema, emptyList())

            assertThat(result.compatible).isTrue()
            assertThat(result.errors).isEmpty()
            assertThat(result.migrations).isEmpty()
        }

        @Test
        fun `returns compatible when all examples match schema`() {
            val schema = createSchema("""{"type": "object", "properties": {"name": {"type": "string"}}}""")
            val examples = listOf(
                createExample("1", "Example 1", """{"name": "John"}"""),
                createExample("2", "Example 2", """{"name": "Jane"}"""),
            )

            val result = validator.analyzeCompatibility(schema, examples)

            assertThat(result.compatible).isTrue()
            assertThat(result.errors).isEmpty()
            assertThat(result.migrations).isEmpty()
        }

        @Test
        fun `detects type mismatch when string expected but number provided`() {
            val schema = createSchema("""{"type": "object", "properties": {"age": {"type": "string"}}}""")
            val examples = listOf(
                createExample("1", "Example 1", """{"age": 42}"""),
            )

            val result = validator.analyzeCompatibility(schema, examples)

            assertThat(result.compatible).isFalse()
            assertThat(result.migrations).hasSize(1)
            assertThat(result.migrations[0].exampleId).isEqualTo("1")
            assertThat(result.migrations[0].exampleName).isEqualTo("Example 1")
            assertThat(result.migrations[0].issue).isEqualTo(ValidationIssueType.TYPE_MISMATCH)
            assertThat(result.migrations[0].expectedType).isEqualTo(ExpectedType.STRING)
            assertThat(result.migrations[0].autoMigratable).isTrue()
            assertThat(result.migrations[0].suggestedValue?.asText()).isEqualTo("42")
        }

        @Test
        fun `detects type mismatch when number expected but string provided`() {
            val schema = createSchema("""{"type": "object", "properties": {"count": {"type": "integer"}}}""")
            val examples = listOf(
                createExample("1", "Example 1", """{"count": "42"}"""),
            )

            val result = validator.analyzeCompatibility(schema, examples)

            assertThat(result.compatible).isFalse()
            assertThat(result.migrations).hasSize(1)
            assertThat(result.migrations[0].autoMigratable).isTrue()
            assertThat(result.migrations[0].suggestedValue?.asLong()).isEqualTo(42)
        }

        @Test
        fun `marks non-numeric string as not auto-migratable to number`() {
            val schema = createSchema("""{"type": "object", "properties": {"count": {"type": "integer"}}}""")
            val examples = listOf(
                createExample("1", "Example 1", """{"count": "hello"}"""),
            )

            val result = validator.analyzeCompatibility(schema, examples)

            assertThat(result.compatible).isFalse()
            assertThat(result.migrations).hasSize(1)
            assertThat(result.migrations[0].autoMigratable).isFalse()
            assertThat(result.migrations[0].suggestedValue).isNull()
        }

        @Test
        fun `marks complex object as not auto-migratable to string`() {
            val schema = createSchema("""{"type": "object", "properties": {"data": {"type": "string"}}}""")
            val examples = listOf(
                createExample("1", "Example 1", """{"data": {"nested": "value"}}"""),
            )

            val result = validator.analyzeCompatibility(schema, examples)

            assertThat(result.compatible).isFalse()
            assertThat(result.migrations).hasSize(1)
            assertThat(result.migrations[0].autoMigratable).isFalse()
        }

        @Test
        fun `missing required property with slash in name keeps escaped pointer path`() {
            val schema = createSchema(
                """
                {
                  "type": "object",
                  "properties": {
                    "a/b": { "type": "string" }
                  },
                  "required": ["a/b"]
                }
                """.trimIndent(),
            )
            val examples = listOf(createExample("1", "Example 1", "{}"))

            val result = validator.analyzeCompatibility(schema, examples)

            assertThat(result.compatible).isFalse()
            assertThat(result.migrations).hasSize(1)
            assertThat(result.migrations[0].issue).isEqualTo(ValidationIssueType.MISSING_REQUIRED)
            assertThat(result.migrations[0].path).isEqualTo("/a~1b")
            assertThat(result.migrations[0].expectedType).isEqualTo(ExpectedType.STRING)
        }

        @Test
        fun `missing required property inside array object resolves item path`() {
            val schema = createSchema(
                """
                {
                  "type": "object",
                  "properties": {
                    "items": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "name": {"type": "string"}
                        },
                        "required": ["name"]
                      }
                    }
                  }
                }
                """.trimIndent(),
            )
            val examples = listOf(createExample("1", "Example 1", """{"items":[{}]}"""))

            val result = validator.analyzeCompatibility(schema, examples)

            assertThat(result.compatible).isFalse()
            assertThat(result.migrations).hasSize(1)
            assertThat(result.migrations[0].issue).isEqualTo(ValidationIssueType.MISSING_REQUIRED)
            assertThat(result.migrations[0].path).isEqualTo("/items/0/name")
            assertThat(result.migrations[0].expectedType).isEqualTo(ExpectedType.STRING)
        }
    }

    @Nested
    inner class ExpectedTypeResolutionTest {
        @Test
        fun `getExpectedType maps primitive and container schema types`() {
            val schema = createSchema(
                """
                {
                  "type": "object",
                  "properties": {
                    "s": {"type": "string"},
                    "n": {"type": "number"},
                    "i": {"type": "integer"},
                    "b": {"type": "boolean"},
                    "d": {"type": "date"},
                    "a": {"type": "array", "items": {"type": "string"}},
                    "o": {"type": "object", "properties": {"x": {"type": "string"}}}
                  }
                }
                """.trimIndent(),
            )

            assertThat(invokeGetExpectedType(schema, "/s")).isEqualTo(ExpectedType.STRING)
            assertThat(invokeGetExpectedType(schema, "/n")).isEqualTo(ExpectedType.NUMBER)
            assertThat(invokeGetExpectedType(schema, "/i")).isEqualTo(ExpectedType.INTEGER)
            assertThat(invokeGetExpectedType(schema, "/b")).isEqualTo(ExpectedType.BOOLEAN)
            assertThat(invokeGetExpectedType(schema, "/d")).isEqualTo(ExpectedType.DATE)
            assertThat(invokeGetExpectedType(schema, "/a")).isEqualTo(ExpectedType.ARRAY)
            assertThat(invokeGetExpectedType(schema, "/o")).isEqualTo(ExpectedType.OBJECT)
        }

        @Test
        fun `getExpectedType returns UNKNOWN when schema path is missing`() {
            val schema = createSchema("""{"properties":{"name":{"type":"string"}}}""")

            assertThat(invokeGetExpectedType(schema, "/missing")).isEqualTo(ExpectedType.UNKNOWN)
        }
    }

    @Nested
    inner class TypeConversionTest {

        @Test
        fun `converts boolean string true to boolean`() {
            val schema = createSchema("""{"type": "object", "properties": {"active": {"type": "boolean"}}}""")
            val examples = listOf(
                createExample("1", "Example 1", """{"active": "true"}"""),
            )

            val result = validator.analyzeCompatibility(schema, examples)

            assertThat(result.migrations).hasSize(1)
            assertThat(result.migrations[0].autoMigratable).isTrue()
            assertThat(result.migrations[0].suggestedValue?.asBoolean()).isTrue()
        }

        @Test
        fun `converts boolean string false to boolean`() {
            val schema = createSchema("""{"type": "object", "properties": {"active": {"type": "boolean"}}}""")
            val examples = listOf(
                createExample("1", "Example 1", """{"active": "false"}"""),
            )

            val result = validator.analyzeCompatibility(schema, examples)

            assertThat(result.migrations).hasSize(1)
            assertThat(result.migrations[0].autoMigratable).isTrue()
            assertThat(result.migrations[0].suggestedValue?.asBoolean()).isFalse()
        }

        @Test
        fun `converts number 1 to boolean true`() {
            val schema = createSchema("""{"type": "object", "properties": {"active": {"type": "boolean"}}}""")
            val examples = listOf(
                createExample("1", "Example 1", """{"active": 1}"""),
            )

            val result = validator.analyzeCompatibility(schema, examples)

            assertThat(result.migrations).hasSize(1)
            assertThat(result.migrations[0].autoMigratable).isTrue()
            assertThat(result.migrations[0].suggestedValue?.asBoolean()).isTrue()
        }

        @Test
        fun `converts number 0 to boolean false`() {
            val schema = createSchema("""{"type": "object", "properties": {"active": {"type": "boolean"}}}""")
            val examples = listOf(
                createExample("1", "Example 1", """{"active": 0}"""),
            )

            val result = validator.analyzeCompatibility(schema, examples)

            assertThat(result.migrations).hasSize(1)
            assertThat(result.migrations[0].autoMigratable).isTrue()
            assertThat(result.migrations[0].suggestedValue?.asBoolean()).isFalse()
        }

        @Test
        fun `converts boolean to string`() {
            val schema = createSchema("""{"type": "object", "properties": {"flag": {"type": "string"}}}""")
            val examples = listOf(
                createExample("1", "Example 1", """{"flag": true}"""),
            )

            val result = validator.analyzeCompatibility(schema, examples)

            assertThat(result.migrations).hasSize(1)
            assertThat(result.migrations[0].autoMigratable).isTrue()
            assertThat(result.migrations[0].suggestedValue?.asText()).isEqualTo("true")
        }

        @Test
        fun `converts decimal string to number`() {
            val schema = createSchema("""{"type": "object", "properties": {"price": {"type": "number"}}}""")
            val examples = listOf(
                createExample("1", "Example 1", """{"price": "19.99"}"""),
            )

            val result = validator.analyzeCompatibility(schema, examples)

            assertThat(result.migrations).hasSize(1)
            assertThat(result.migrations[0].autoMigratable).isTrue()
            assertThat(result.migrations[0].suggestedValue?.asDouble()).isEqualTo(19.99)
        }
    }

    @Nested
    inner class MultipleExamplesTest {

        @Test
        fun `handles multiple examples with different issues`() {
            val schema = createSchema(
                """{"type": "object", "properties": {"count": {"type": "integer"}, "name": {"type": "string"}}}""",
            )
            val examples = listOf(
                createExample("1", "Example 1", """{"count": "42", "name": "John"}"""),
                createExample("2", "Example 2", """{"count": 10, "name": 123}"""),
            )

            val result = validator.analyzeCompatibility(schema, examples)

            assertThat(result.compatible).isFalse()
            assertThat(result.migrations).hasSize(2)

            val migration1 = result.migrations.find { it.exampleId == "1" }
            assertThat(migration1).isNotNull
            assertThat(migration1!!.path).contains("count")

            val migration2 = result.migrations.find { it.exampleId == "2" }
            assertThat(migration2).isNotNull
            assertThat(migration2!!.path).contains("name")
        }

        @Test
        fun `returns compatible when some examples match and others are empty`() {
            val schema = createSchema("""{"type": "object", "properties": {"name": {"type": "string"}}}""")
            val examples = listOf(
                createExample("1", "Example 1", """{"name": "John"}"""),
                createExample("2", "Example 2", """{}"""),
            )

            val result = validator.analyzeCompatibility(schema, examples)

            assertThat(result.compatible).isTrue()
        }
    }

    @Nested
    inner class SchemaCompatibilityResultTest {

        @Test
        fun `compatible factory creates result with empty collections`() {
            val result = SchemaCompatibilityResult.compatible()

            assertThat(result.compatible).isTrue()
            assertThat(result.errors).isEmpty()
            assertThat(result.migrations).isEmpty()
        }
    }

    private fun invokeGetExpectedType(schema: ObjectNode, path: String): ExpectedType {
        val method = JsonSchemaValidator::class.java.getDeclaredMethod(
            "getExpectedType",
            ObjectNode::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(validator, schema, path) as ExpectedType
    }
}
