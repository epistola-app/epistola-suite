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
                assertDoesNotThrow { TemplateKey.of(slug) }
            }
        }

        @Test
        fun `should accept minimum length slug`() {
            val id = TemplateKey.of("abc")
            assertEquals("abc", id.value)
        }

        @Test
        fun `should accept maximum length slug`() {
            val slug = "a" + "b".repeat(49) // 50 characters
            val id = TemplateKey.of(slug)
            assertEquals(50, id.value.length)
        }

        @Test
        fun `should accept slug with hyphens`() {
            val id = TemplateKey.of("my-template-name")
            assertEquals("my-template-name", id.value)
        }

        @Test
        fun `should accept slug with numbers`() {
            val id = TemplateKey.of("template123")
            assertEquals("template123", id.value)
        }

        @Test
        fun `validateOrNull should return TemplateId for valid slug`() {
            val result = TemplateKey.validateOrNull("valid-slug")
            assertNotNull(result)
            assertEquals("valid-slug", result.value)
        }
    }

    @Nested
    inner class InvalidSlugs {
        @Test
        fun `should reject slug shorter than 3 characters`() {
            val exception = assertThrows<IllegalArgumentException> { TemplateKey.of("ab") }
            assertEquals("Template ID must be 3-50 characters, got 2", exception.message)
        }

        @Test
        fun `should reject slug longer than 50 characters`() {
            val slug = "a" + "b".repeat(50) // 51 characters
            val exception = assertThrows<IllegalArgumentException> { TemplateKey.of(slug) }
            assertEquals("Template ID must be 3-50 characters, got 51", exception.message)
        }

        @Test
        fun `should reject slug starting with number`() {
            assertThrows<IllegalArgumentException> { TemplateKey.of("123abc") }
        }

        @Test
        fun `should reject slug starting with hyphen`() {
            assertThrows<IllegalArgumentException> { TemplateKey.of("-abc") }
        }

        @Test
        fun `should reject slug ending with hyphen`() {
            assertThrows<IllegalArgumentException> { TemplateKey.of("abc-") }
        }

        @Test
        fun `should reject slug with consecutive hyphens`() {
            assertThrows<IllegalArgumentException> { TemplateKey.of("abc--def") }
        }

        @Test
        fun `should reject slug with uppercase letters`() {
            assertThrows<IllegalArgumentException> { TemplateKey.of("MyTemplate") }
        }

        @Test
        fun `should reject slug with spaces`() {
            assertThrows<IllegalArgumentException> { TemplateKey.of("my template") }
        }

        @Test
        fun `should reject slug with underscores`() {
            assertThrows<IllegalArgumentException> { TemplateKey.of("my_template") }
        }

        @Test
        fun `should reject slug with special characters`() {
            assertThrows<IllegalArgumentException> { TemplateKey.of("my.template") }
            assertThrows<IllegalArgumentException> { TemplateKey.of("my@template") }
            assertThrows<IllegalArgumentException> { TemplateKey.of("my!template") }
        }

        @Test
        fun `should reject empty slug`() {
            assertThrows<IllegalArgumentException> { TemplateKey.of("") }
        }

        @Test
        fun `should reject reserved words`() {
            assertThrows<IllegalArgumentException> { TemplateKey.of("admin") }
            assertThrows<IllegalArgumentException> { TemplateKey.of("api") }
            assertThrows<IllegalArgumentException> { TemplateKey.of("www") }
            assertThrows<IllegalArgumentException> { TemplateKey.of("system") }
            assertThrows<IllegalArgumentException> { TemplateKey.of("internal") }
            assertThrows<IllegalArgumentException> { TemplateKey.of("null") }
            assertThrows<IllegalArgumentException> { TemplateKey.of("undefined") }
            assertThrows<IllegalArgumentException> { TemplateKey.of("new") }
            assertThrows<IllegalArgumentException> { TemplateKey.of("create") }
            assertThrows<IllegalArgumentException> { TemplateKey.of("edit") }
            assertThrows<IllegalArgumentException> { TemplateKey.of("delete") }
        }

        @Test
        fun `validateOrNull should return null for invalid slug`() {
            assertNull(TemplateKey.validateOrNull(""))
            assertNull(TemplateKey.validateOrNull("ab"))
            assertNull(TemplateKey.validateOrNull("ABC"))
            assertNull(TemplateKey.validateOrNull("a" + "b".repeat(50))) // 51 chars
            assertNull(TemplateKey.validateOrNull("admin")) // reserved word
        }
    }

    @Nested
    inner class ValueClassBehavior {
        @Test
        fun `toString should return the slug value`() {
            val id = TemplateKey.of("my-template")
            assertEquals("my-template", id.toString())
        }

        @Test
        fun `value property should return the slug`() {
            val id = TemplateKey.of("monthly-invoice")
            assertEquals("monthly-invoice", id.value)
        }

        @Test
        fun `equal slugs should be equal`() {
            val id1 = TemplateKey.of("same-slug")
            val id2 = TemplateKey.of("same-slug")
            assertEquals(id1, id2)
        }
    }
}
