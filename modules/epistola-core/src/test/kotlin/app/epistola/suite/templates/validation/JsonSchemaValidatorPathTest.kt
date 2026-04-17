package app.epistola.suite.templates.validation

import app.epistola.suite.templates.model.DataExample
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

class JsonSchemaValidatorPathTest {
    private lateinit var objectMapper: ObjectMapper
    private lateinit var validator: JsonSchemaValidator

    @BeforeEach
    fun setUp() {
        objectMapper = ObjectMapper()
        validator = JsonSchemaValidator(objectMapper)
    }

    @Test
    fun `required property path escapes slash token in json pointer`() {
        val schema = parseSchema(
            """
            {
              "type": "object",
              "properties": { "foo/bar": { "type": "string" } },
              "required": ["foo/bar"]
            }
            """.trimIndent(),
        )

        val errors = validator.validate(schema, objectMapper.createObjectNode())

        assertThat(errors).hasSize(1)
        assertThat(errors.first().path).isEqualTo("/foo~1bar")
    }

    @Test
    fun `required property path escapes tilde token in json pointer`() {
        val schema = parseSchema(
            """
            {
              "type": "object",
              "properties": { "foo~bar": { "type": "string" } },
              "required": ["foo~bar"]
            }
            """.trimIndent(),
        )

        val errors = validator.validate(schema, objectMapper.createObjectNode())

        assertThat(errors).hasSize(1)
        assertThat(errors.first().path).isEqualTo("/foo~0bar")
    }

    @Test
    fun `analyzeCompatibility decodes escaped pointer segments when detecting expected type`() {
        val schema = parseSchema(
            """
            {
              "type": "object",
              "properties": {
                "a/b": { "type": "integer" }
              }
            }
            """.trimIndent(),
        )

        val example = DataExample(
            id = "ex-1",
            name = "Example",
            data = objectMapper.createObjectNode().put("a/b", "42"),
        )

        val result = validator.analyzeCompatibility(schema, listOf(example))

        assertThat(result.compatible).isFalse()
        assertThat(result.migrations).hasSize(1)

        val migration = result.migrations.first()
        assertThat(migration.path).isEqualTo("/a~1b")
        assertThat(migration.expectedType).isEqualTo(ExpectedType.INTEGER)
        assertThat(migration.currentValue?.asText()).isEqualTo("42")
        assertThat(migration.suggestedValue?.asInt()).isEqualTo(42)
        assertThat(migration.autoMigratable).isTrue()
    }

    private fun parseSchema(json: String): ObjectNode = objectMapper.readValue(json, ObjectNode::class.java)
}
