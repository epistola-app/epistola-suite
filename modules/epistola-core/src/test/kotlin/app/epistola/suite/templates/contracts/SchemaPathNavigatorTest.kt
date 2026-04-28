package app.epistola.suite.templates.contracts

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

class SchemaPathNavigatorTest {

    private lateinit var navigator: SchemaPathNavigator
    private val objectMapper = ObjectMapper()

    @BeforeEach
    fun setUp() {
        navigator = SchemaPathNavigator()
    }

    private fun schema(json: String): ObjectNode = objectMapper.readValue(json, ObjectNode::class.java)

    @Nested
    inner class SimpleFields {
        @Test
        fun `resolves simple string field`() {
            val s = schema("""{"type":"object","properties":{"name":{"type":"string"}}}""")
            val result = navigator.resolve(s, "name")
            assertThat(result.found).isTrue()
            assertThat(result.type).isEqualTo("string")
        }

        @Test
        fun `resolves number field`() {
            val s = schema("""{"type":"object","properties":{"amount":{"type":"number"}}}""")
            val result = navigator.resolve(s, "amount")
            assertThat(result.found).isTrue()
            assertThat(result.type).isEqualTo("number")
        }

        @Test
        fun `returns not found for missing field`() {
            val s = schema("""{"type":"object","properties":{"name":{"type":"string"}}}""")
            val result = navigator.resolve(s, "missing")
            assertThat(result.found).isFalse()
            assertThat(result.type).isEqualTo("unknown")
        }
    }

    @Nested
    inner class NestedObjects {
        @Test
        fun `resolves nested object field`() {
            val s = schema("""{"type":"object","properties":{"customer":{"type":"object","properties":{"name":{"type":"string"}}}}}""")
            val result = navigator.resolve(s, "customer.name")
            assertThat(result.found).isTrue()
            assertThat(result.type).isEqualTo("string")
        }

        @Test
        fun `resolves deeply nested field`() {
            val s = schema("""{"type":"object","properties":{"customer":{"type":"object","properties":{"address":{"type":"object","properties":{"city":{"type":"string"}}}}}}}""")
            val result = navigator.resolve(s, "customer.address.city")
            assertThat(result.found).isTrue()
            assertThat(result.type).isEqualTo("string")
        }

        @Test
        fun `resolves parent object type`() {
            val s = schema("""{"type":"object","properties":{"customer":{"type":"object","properties":{"name":{"type":"string"}}}}}""")
            val result = navigator.resolve(s, "customer")
            assertThat(result.found).isTrue()
            assertThat(result.type).isEqualTo("object")
        }
    }

    @Nested
    inner class ArrayFields {
        @Test
        fun `resolves array type`() {
            val s = schema("""{"type":"object","properties":{"items":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"}}}}}}""")
            val result = navigator.resolve(s, "items")
            assertThat(result.found).isTrue()
            assertThat(result.type).isEqualTo("array")
        }

        @Test
        fun `resolves field inside array items`() {
            val s = schema("""{"type":"object","properties":{"items":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"}}}}}}""")
            val result = navigator.resolve(s, "items[*].name")
            assertThat(result.found).isTrue()
            assertThat(result.type).isEqualTo("string")
        }

        @Test
        fun `resolves nested array items`() {
            val s = schema("""{"type":"object","properties":{"orders":{"type":"array","items":{"type":"object","properties":{"items":{"type":"array","items":{"type":"object","properties":{"price":{"type":"number"}}}}}}}}}""")
            val result = navigator.resolve(s, "orders[*].items[*].price")
            assertThat(result.found).isTrue()
            assertThat(result.type).isEqualTo("number")
        }

        @Test
        fun `returns not found for missing field in array items`() {
            val s = schema("""{"type":"object","properties":{"items":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"}}}}}}""")
            val result = navigator.resolve(s, "items[*].missing")
            assertThat(result.found).isFalse()
        }
    }

    @Nested
    inner class EdgeCases {
        @Test
        fun `empty path returns not found`() {
            val s = schema("""{"type":"object","properties":{"name":{"type":"string"}}}""")
            val result = navigator.resolve(s, "")
            assertThat(result.found).isFalse()
        }

        @Test
        fun `schema with no properties returns not found`() {
            val s = schema("""{"type":"object"}""")
            val result = navigator.resolve(s, "name")
            assertThat(result.found).isFalse()
        }
    }
}
