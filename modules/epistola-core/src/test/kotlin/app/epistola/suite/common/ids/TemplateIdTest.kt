package app.epistola.suite.common.ids

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TemplateIdTest {

    @Nested
    inner class ValidSlugs {
        @ParameterizedTest
        @ValueSource(
            strings = [
                "abc", // minimum length
                "monthly-invoice", // simple
                "my-template", // with hyphen
                "my-template-2024", // multiple hyphens
                "abc123", // alphanumeric
                "template1", // ends with number
                "a2b3c4", // mixed alphanumeric
                "welcome-email", // typical usage
                "quarterly-report", // typical usage
            ],
        )
        fun `should accept valid slugs`(slug: String) {
            if (slug.length in 3..50) {
                assertDoesNotThrow { TemplateId.of(slug) }
            }
        }

        @Test
        fun `should accept minimum length slug`() {
            val id = TemplateId.of("abc")
            assertEquals("abc", id.value)
        }

        @Test
        fun `should accept maximum length slug`() {
            val slug = "a" + "b".repeat(49) // 50 characters
            val id = TemplateId.of(slug)
            assertEquals(50, id.value.length)
        }

        @Test
        fun `should accept slug with hyphens`() {
            val id = TemplateId.of("my-template-name")
            assertEquals("my-template-name", id.value)
        }

        @Test
        fun `should accept slug with numbers`() {
            val id = TemplateId.of("template123")
            assertEquals("template123", id.value)
        }

        @Test
        fun `validateOrNull should return TemplateId for valid slug`() {
            val result = TemplateId.validateOrNull("valid-slug")
            assertNotNull(result)
            assertEquals("valid-slug", result.value)
        }
    }

    @Nested
    inner class InvalidSlugs {
        @Test
        fun `should reject slug shorter than 3 characters`() {
            val exception = assertThrows<IllegalArgumentException> { TemplateId.of("ab") }
            assertEquals("Template ID must be 3-50 characters, got 2", exception.message)
        }

        @Test
        fun `should reject slug longer than 50 characters`() {
            val slug = "a" + "b".repeat(50) // 51 characters
            val exception = assertThrows<IllegalArgumentException> { TemplateId.of(slug) }
            assertEquals("Template ID must be 3-50 characters, got 51", exception.message)
        }

        @Test
        fun `should reject slug starting with number`() {
            assertThrows<IllegalArgumentException> { TemplateId.of("123abc") }
        }

        @Test
        fun `should reject slug starting with hyphen`() {
            assertThrows<IllegalArgumentException> { TemplateId.of("-abc") }
        }

        @Test
        fun `should reject slug ending with hyphen`() {
            assertThrows<IllegalArgumentException> { TemplateId.of("abc-") }
        }

        @Test
        fun `should reject slug with consecutive hyphens`() {
            assertThrows<IllegalArgumentException> { TemplateId.of("abc--def") }
        }

        @Test
        fun `should reject slug with uppercase letters`() {
            assertThrows<IllegalArgumentException> { TemplateId.of("MyTemplate") }
        }

        @Test
        fun `should reject slug with spaces`() {
            assertThrows<IllegalArgumentException> { TemplateId.of("my template") }
        }

        @Test
        fun `should reject slug with underscores`() {
            assertThrows<IllegalArgumentException> { TemplateId.of("my_template") }
        }

        @Test
        fun `should reject slug with special characters`() {
            assertThrows<IllegalArgumentException> { TemplateId.of("my.template") }
            assertThrows<IllegalArgumentException> { TemplateId.of("my@template") }
            assertThrows<IllegalArgumentException> { TemplateId.of("my!template") }
        }

        @Test
        fun `should reject empty slug`() {
            assertThrows<IllegalArgumentException> { TemplateId.of("") }
        }

        @Test
        fun `should reject reserved words`() {
            assertThrows<IllegalArgumentException> { TemplateId.of("admin") }
            assertThrows<IllegalArgumentException> { TemplateId.of("api") }
            assertThrows<IllegalArgumentException> { TemplateId.of("www") }
            assertThrows<IllegalArgumentException> { TemplateId.of("system") }
            assertThrows<IllegalArgumentException> { TemplateId.of("internal") }
            assertThrows<IllegalArgumentException> { TemplateId.of("null") }
            assertThrows<IllegalArgumentException> { TemplateId.of("undefined") }
            assertThrows<IllegalArgumentException> { TemplateId.of("default") }
            assertThrows<IllegalArgumentException> { TemplateId.of("new") }
            assertThrows<IllegalArgumentException> { TemplateId.of("create") }
            assertThrows<IllegalArgumentException> { TemplateId.of("edit") }
            assertThrows<IllegalArgumentException> { TemplateId.of("delete") }
        }

        @Test
        fun `validateOrNull should return null for invalid slug`() {
            assertNull(TemplateId.validateOrNull(""))
            assertNull(TemplateId.validateOrNull("ab"))
            assertNull(TemplateId.validateOrNull("ABC"))
            assertNull(TemplateId.validateOrNull("a" + "b".repeat(50))) // 51 chars
            assertNull(TemplateId.validateOrNull("admin")) // reserved word
        }
    }

    @Nested
    inner class ValueClassBehavior {
        @Test
        fun `toString should return the slug value`() {
            val id = TemplateId.of("my-template")
            assertEquals("my-template", id.toString())
        }

        @Test
        fun `value property should return the slug`() {
            val id = TemplateId.of("monthly-invoice")
            assertEquals("monthly-invoice", id.value)
        }

        @Test
        fun `equal slugs should be equal`() {
            val id1 = TemplateId.of("same-slug")
            val id2 = TemplateId.of("same-slug")
            assertEquals(id1, id2)
        }
    }
}
