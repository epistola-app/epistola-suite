package app.epistola.suite.templates.validation

import app.epistola.suite.templates.validation.SchemaCompatibilityChecker.BreakingChangeType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

class SchemaCompatibilityCheckerTest {

    private lateinit var checker: SchemaCompatibilityChecker
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        checker = SchemaCompatibilityChecker()
        objectMapper = ObjectMapper()
    }

    private fun schema(json: String): ObjectNode = objectMapper.readValue(json, ObjectNode::class.java)

    @Nested
    inner class NullHandling {
        @Test
        fun `both null is compatible`() {
            val result = checker.checkCompatibility(null, null)
            assertThat(result.compatible).isTrue()
        }

        @Test
        fun `adding schema where there was none is compatible`() {
            val result = checker.checkCompatibility(null, schema("""{"type":"object","properties":{"name":{"type":"string"}}}"""))
            assertThat(result.compatible).isTrue()
        }

        @Test
        fun `removing schema entirely is breaking`() {
            val result = checker.checkCompatibility(schema("""{"type":"object","properties":{"name":{"type":"string"}}}"""), null)
            assertThat(result.compatible).isFalse()
            assertThat(result.breakingChanges).hasSize(1)
            assertThat(result.breakingChanges[0].type).isEqualTo(BreakingChangeType.FIELD_REMOVED)
        }
    }

    @Nested
    inner class FieldChanges {
        @Test
        fun `adding optional field is compatible`() {
            val old = schema("""{"type":"object","properties":{"name":{"type":"string"}}}""")
            val new = schema("""{"type":"object","properties":{"name":{"type":"string"},"age":{"type":"integer"}}}""")
            val result = checker.checkCompatibility(old, new)
            assertThat(result.compatible).isTrue()
        }

        @Test
        fun `removing field is breaking`() {
            val old = schema("""{"type":"object","properties":{"name":{"type":"string"},"age":{"type":"integer"}}}""")
            val new = schema("""{"type":"object","properties":{"name":{"type":"string"}}}""")
            val result = checker.checkCompatibility(old, new)
            assertThat(result.compatible).isFalse()
            assertThat(result.breakingChanges).hasSize(1)
            assertThat(result.breakingChanges[0].type).isEqualTo(BreakingChangeType.FIELD_REMOVED)
            assertThat(result.breakingChanges[0].path).isEqualTo("age")
        }

        @Test
        fun `changing field type is breaking`() {
            val old = schema("""{"type":"object","properties":{"age":{"type":"integer"}}}""")
            val new = schema("""{"type":"object","properties":{"age":{"type":"string"}}}""")
            val result = checker.checkCompatibility(old, new)
            assertThat(result.compatible).isFalse()
            assertThat(result.breakingChanges).hasSize(1)
            assertThat(result.breakingChanges[0].type).isEqualTo(BreakingChangeType.TYPE_CHANGED)
        }

        @Test
        fun `changing description is compatible`() {
            val old = schema("""{"type":"object","properties":{"name":{"type":"string","description":"old"}}}""")
            val new = schema("""{"type":"object","properties":{"name":{"type":"string","description":"new"}}}""")
            val result = checker.checkCompatibility(old, new)
            assertThat(result.compatible).isTrue()
        }
    }

    @Nested
    inner class RequiredChanges {
        @Test
        fun `adding new required field is breaking`() {
            val old = schema("""{"type":"object","properties":{"name":{"type":"string"}}}""")
            val new = schema("""{"type":"object","properties":{"name":{"type":"string"},"age":{"type":"integer"}},"required":["age"]}""")
            val result = checker.checkCompatibility(old, new)
            assertThat(result.compatible).isFalse()
            assertThat(result.breakingChanges).anyMatch { it.type == BreakingChangeType.REQUIRED_ADDED }
        }

        @Test
        fun `making optional field required is breaking`() {
            val old = schema("""{"type":"object","properties":{"name":{"type":"string"}}}""")
            val new = schema("""{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""")
            val result = checker.checkCompatibility(old, new)
            assertThat(result.compatible).isFalse()
            assertThat(result.breakingChanges).hasSize(1)
            assertThat(result.breakingChanges[0].type).isEqualTo(BreakingChangeType.MADE_REQUIRED)
        }

        @Test
        fun `keeping required field required is compatible`() {
            val old = schema("""{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""")
            val new = schema("""{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""")
            val result = checker.checkCompatibility(old, new)
            assertThat(result.compatible).isTrue()
        }

        @Test
        fun `making required field optional is compatible`() {
            val old = schema("""{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""")
            val new = schema("""{"type":"object","properties":{"name":{"type":"string"}}}""")
            val result = checker.checkCompatibility(old, new)
            assertThat(result.compatible).isTrue()
        }
    }

    @Nested
    inner class ConstraintNarrowing {
        @Test
        fun `adding enum constraint is breaking`() {
            val old = schema("""{"type":"object","properties":{"status":{"type":"string"}}}""")
            val new = schema("""{"type":"object","properties":{"status":{"type":"string","enum":["active","inactive"]}}}""")
            val result = checker.checkCompatibility(old, new)
            assertThat(result.compatible).isFalse()
            assertThat(result.breakingChanges).hasSize(1)
            assertThat(result.breakingChanges[0].type).isEqualTo(BreakingChangeType.CONSTRAINT_NARROWED)
        }

        @Test
        fun `removing enum values is breaking`() {
            val old = schema("""{"type":"object","properties":{"status":{"type":"string","enum":["a","b","c"]}}}""")
            val new = schema("""{"type":"object","properties":{"status":{"type":"string","enum":["a","b"]}}}""")
            val result = checker.checkCompatibility(old, new)
            assertThat(result.compatible).isFalse()
            assertThat(result.breakingChanges).anyMatch { it.description.contains("enum values removed") }
        }

        @Test
        fun `adding enum values is compatible`() {
            val old = schema("""{"type":"object","properties":{"status":{"type":"string","enum":["a","b"]}}}""")
            val new = schema("""{"type":"object","properties":{"status":{"type":"string","enum":["a","b","c"]}}}""")
            val result = checker.checkCompatibility(old, new)
            assertThat(result.compatible).isTrue()
        }

        @Test
        fun `increasing minimum is breaking`() {
            val old = schema("""{"type":"object","properties":{"age":{"type":"integer","minimum":0}}}""")
            val new = schema("""{"type":"object","properties":{"age":{"type":"integer","minimum":18}}}""")
            val result = checker.checkCompatibility(old, new)
            assertThat(result.compatible).isFalse()
            assertThat(result.breakingChanges).anyMatch { it.description.contains("minimum") }
        }

        @Test
        fun `decreasing minimum is compatible`() {
            val old = schema("""{"type":"object","properties":{"age":{"type":"integer","minimum":18}}}""")
            val new = schema("""{"type":"object","properties":{"age":{"type":"integer","minimum":0}}}""")
            val result = checker.checkCompatibility(old, new)
            assertThat(result.compatible).isTrue()
        }

        @Test
        fun `decreasing maximum is breaking`() {
            val old = schema("""{"type":"object","properties":{"count":{"type":"integer","maximum":100}}}""")
            val new = schema("""{"type":"object","properties":{"count":{"type":"integer","maximum":50}}}""")
            val result = checker.checkCompatibility(old, new)
            assertThat(result.compatible).isFalse()
        }

        @Test
        fun `adding maxLength is breaking`() {
            val old = schema("""{"type":"object","properties":{"name":{"type":"string"}}}""")
            val new = schema("""{"type":"object","properties":{"name":{"type":"string","maxLength":50}}}""")
            val result = checker.checkCompatibility(old, new)
            assertThat(result.compatible).isFalse()
        }

        @Test
        fun `adding pattern is breaking`() {
            val old = schema("""{"type":"object","properties":{"code":{"type":"string"}}}""")
            val new = schema("""{"type":"object","properties":{"code":{"type":"string","pattern":"^[A-Z]+$"}}}""")
            val result = checker.checkCompatibility(old, new)
            assertThat(result.compatible).isFalse()
            assertThat(result.breakingChanges).anyMatch { it.description.contains("pattern") }
        }
    }

    @Nested
    inner class NestedObjects {
        @Test
        fun `removing nested field is breaking`() {
            val old = schema("""{"type":"object","properties":{"address":{"type":"object","properties":{"street":{"type":"string"},"city":{"type":"string"}}}}}""")
            val new = schema("""{"type":"object","properties":{"address":{"type":"object","properties":{"street":{"type":"string"}}}}}""")
            val result = checker.checkCompatibility(old, new)
            assertThat(result.compatible).isFalse()
            assertThat(result.breakingChanges).hasSize(1)
            assertThat(result.breakingChanges[0].path).isEqualTo("address.city")
        }

        @Test
        fun `adding optional nested field is compatible`() {
            val old = schema("""{"type":"object","properties":{"address":{"type":"object","properties":{"street":{"type":"string"}}}}}""")
            val new = schema("""{"type":"object","properties":{"address":{"type":"object","properties":{"street":{"type":"string"},"city":{"type":"string"}}}}}""")
            val result = checker.checkCompatibility(old, new)
            assertThat(result.compatible).isTrue()
        }
    }

    @Nested
    inner class ArrayItems {
        @Test
        fun `changing array item type is breaking`() {
            val old = schema("""{"type":"object","properties":{"tags":{"type":"array","items":{"type":"string"}}}}""")
            val new = schema("""{"type":"object","properties":{"tags":{"type":"array","items":{"type":"integer"}}}}""")
            val result = checker.checkCompatibility(old, new)
            assertThat(result.compatible).isFalse()
            assertThat(result.breakingChanges).anyMatch { it.type == BreakingChangeType.TYPE_CHANGED }
        }

        @Test
        fun `removing field from array item objects is breaking`() {
            val old = schema("""{"type":"object","properties":{"items":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"price":{"type":"number"}}}}}}""")
            val new = schema("""{"type":"object","properties":{"items":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"}}}}}}""")
            val result = checker.checkCompatibility(old, new)
            assertThat(result.compatible).isFalse()
            assertThat(result.breakingChanges).anyMatch { it.path == "items[].price" }
        }
    }

    @Nested
    inner class MultipleBreakingChanges {
        @Test
        fun `reports all breaking changes`() {
            val old = schema("""{"type":"object","properties":{"name":{"type":"string"},"age":{"type":"integer"},"email":{"type":"string"}}}""")
            val new = schema("""{"type":"object","properties":{"name":{"type":"integer"},"email":{"type":"string"}},"required":["email"]}""")
            val result = checker.checkCompatibility(old, new)
            assertThat(result.compatible).isFalse()
            // name type changed, age removed, email made required
            assertThat(result.breakingChanges).hasSize(3)
            assertThat(result.breakingChanges.map { it.type }).containsExactlyInAnyOrder(
                BreakingChangeType.TYPE_CHANGED,
                BreakingChangeType.FIELD_REMOVED,
                BreakingChangeType.MADE_REQUIRED,
            )
        }
    }

    @Nested
    inner class EmptySchemas {
        @Test
        fun `empty schema to empty schema is compatible`() {
            val result = checker.checkCompatibility(schema("""{"type":"object"}"""), schema("""{"type":"object"}"""))
            assertThat(result.compatible).isTrue()
        }

        @Test
        fun `empty schema to schema with properties is compatible`() {
            val result = checker.checkCompatibility(schema("""{"type":"object"}"""), schema("""{"type":"object","properties":{"name":{"type":"string"}}}"""))
            assertThat(result.compatible).isTrue()
        }
    }
}
