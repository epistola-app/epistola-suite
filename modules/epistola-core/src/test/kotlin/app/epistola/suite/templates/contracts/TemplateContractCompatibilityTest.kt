package app.epistola.suite.templates.contracts

import app.epistola.suite.templates.analysis.IncompatibilityReason
import app.epistola.suite.templates.analysis.TemplateCompatibilityResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Tests [TemplateVersionCompatibilityEvaluator] — the shared comparison used by both the
 * CheckTemplateVersionCompatibility and CheckContractPublishImpact query handlers.
 */
class TemplateContractCompatibilityTest {

    private val evaluator = TemplateVersionCompatibilityEvaluator()
    private val objectMapper = ObjectMapper()

    private fun schema(json: String): ObjectNode = objectMapper.readValue(json, ObjectNode::class.java)

    private val baseSchema = """{"type":"object","properties":{"name":{"type":"string"},"age":{"type":"integer"},"orders":{"type":"array","items":{"type":"object","properties":{"total":{"type":"number"},"status":{"type":"string"}}}}}}"""

    private fun checkCompatibility(paths: Set<String>, oldSchema: ObjectNode?, newSchema: ObjectNode?): TemplateCompatibilityResult = evaluator.evaluate(paths, oldSchema, newSchema)

    @Nested
    inner class Compatible {
        @Test
        fun `empty paths are always compatible`() {
            val result = checkCompatibility(emptySet(), schema(baseSchema), schema("""{"type":"object"}"""))
            assertThat(result.compatible).isTrue()
        }

        @Test
        fun `template using unchanged fields is compatible`() {
            val newSchema = schema("""{"type":"object","properties":{"name":{"type":"string"},"age":{"type":"integer"}}}""")
            val result = checkCompatibility(setOf("name", "age"), schema(baseSchema), newSchema)
            assertThat(result.compatible).isTrue()
        }

        @Test
        fun `template not using removed field is compatible`() {
            val newSchema = schema("""{"type":"object","properties":{"name":{"type":"string"},"age":{"type":"integer"}}}""")
            val result = checkCompatibility(setOf("name"), schema(baseSchema), newSchema)
            assertThat(result.compatible).isTrue()
        }

        @Test
        fun `both schemas null is compatible`() {
            val result = checkCompatibility(setOf("name"), null, null)
            assertThat(result.compatible).isTrue()
        }

        @Test
        fun `new schema added where there was none is compatible`() {
            val result = checkCompatibility(emptySet(), null, schema(baseSchema))
            assertThat(result.compatible).isTrue()
        }
    }

    @Nested
    inner class Incompatible {
        @Test
        fun `field removed that template uses`() {
            val newSchema = schema("""{"type":"object","properties":{"age":{"type":"integer"}}}""")
            val result = checkCompatibility(setOf("name"), schema(baseSchema), newSchema)
            assertThat(result.compatible).isFalse()
            assertThat(result.incompatibilities).hasSize(1)
            assertThat(result.incompatibilities[0].path).isEqualTo("name")
            assertThat(result.incompatibilities[0].reason).isEqualTo(IncompatibilityReason.FIELD_REMOVED)
        }

        @Test
        fun `field type changed that template uses`() {
            val newSchema = schema("""{"type":"object","properties":{"name":{"type":"integer"},"age":{"type":"integer"}}}""")
            val result = checkCompatibility(setOf("name"), schema(baseSchema), newSchema)
            assertThat(result.compatible).isFalse()
            assertThat(result.incompatibilities).hasSize(1)
            assertThat(result.incompatibilities[0].reason).isEqualTo(IncompatibilityReason.TYPE_CHANGED)
        }

        @Test
        fun `schema removed entirely with referenced paths`() {
            val result = checkCompatibility(setOf("name"), schema(baseSchema), null)
            assertThat(result.compatible).isFalse()
            assertThat(result.incompatibilities).hasSize(1)
            assertThat(result.incompatibilities[0].reason).isEqualTo(IncompatibilityReason.FIELD_REMOVED)
        }

        @Test
        fun `array item field removed that template uses`() {
            val newSchema = schema("""{"type":"object","properties":{"orders":{"type":"array","items":{"type":"object","properties":{"status":{"type":"string"}}}}}}""")
            val result = checkCompatibility(setOf("orders[*].total"), schema(baseSchema), newSchema)
            assertThat(result.compatible).isFalse()
            assertThat(result.incompatibilities[0].path).isEqualTo("orders[*].total")
        }

        @Test
        fun `multiple incompatibilities reported`() {
            val newSchema = schema("""{"type":"object","properties":{"age":{"type":"string"}}}""")
            val result = checkCompatibility(setOf("name", "age"), schema(baseSchema), newSchema)
            assertThat(result.compatible).isFalse()
            assertThat(result.incompatibilities).hasSize(2)
        }
    }

    @Nested
    inner class MixedCompatibility {
        @Test
        fun `some fields compatible, some not`() {
            val newSchema = schema("""{"type":"object","properties":{"name":{"type":"string"},"orders":{"type":"array","items":{"type":"object","properties":{"status":{"type":"string"}}}}}}""")
            val result = checkCompatibility(setOf("name", "orders[*].total"), schema(baseSchema), newSchema)
            assertThat(result.compatible).isFalse()
            assertThat(result.incompatibilities).hasSize(1)
            assertThat(result.incompatibilities[0].path).isEqualTo("orders[*].total")
        }
    }
}
