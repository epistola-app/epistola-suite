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

class ThemeIdTest {

    @Nested
    inner class ValidSlugs {
        @ParameterizedTest
        @ValueSource(
            strings = [
                "abc", // minimum length
                "corporate", // simple
                "my-theme", // with hyphen
                "my-theme-2024", // multiple hyphens
                "abc123", // alphanumeric
                "theme1", // ends with number
                "a2b3c4", // mixed alphanumeric
                "modern", // typical usage
                "default", // typical usage
            ],
        )
        fun `should accept valid slugs`(slug: String) {
            if (slug.length in 3..20) {
                assertDoesNotThrow { ThemeId.of(slug) }
            }
        }

        @Test
        fun `should accept minimum length slug`() {
            val id = ThemeId.of("abc")
            assertEquals("abc", id.value)
        }

        @Test
        fun `should accept maximum length slug`() {
            val slug = "a" + "b".repeat(19) // 20 characters
            val id = ThemeId.of(slug)
            assertEquals(20, id.value.length)
        }

        @Test
        fun `should accept slug with hyphens`() {
            val id = ThemeId.of("my-theme-name")
            assertEquals("my-theme-name", id.value)
        }

        @Test
        fun `should accept slug with numbers`() {
            val id = ThemeId.of("theme123")
            assertEquals("theme123", id.value)
        }

        @Test
        fun `validateOrNull should return ThemeId for valid slug`() {
            val result = ThemeId.validateOrNull("valid-slug")
            assertNotNull(result)
            assertEquals("valid-slug", result.value)
        }
    }

    @Nested
    inner class InvalidSlugs {
        @Test
        fun `should reject slug shorter than 3 characters`() {
            val exception = assertThrows<IllegalArgumentException> { ThemeId.of("ab") }
            assertEquals("Theme ID must be 3-20 characters, got 2", exception.message)
        }

        @Test
        fun `should reject slug longer than 20 characters`() {
            val slug = "a" + "b".repeat(20) // 21 characters
            val exception = assertThrows<IllegalArgumentException> { ThemeId.of(slug) }
            assertEquals("Theme ID must be 3-20 characters, got 21", exception.message)
        }

        @Test
        fun `should reject slug starting with number`() {
            assertThrows<IllegalArgumentException> { ThemeId.of("123abc") }
        }

        @Test
        fun `should reject slug starting with hyphen`() {
            assertThrows<IllegalArgumentException> { ThemeId.of("-abc") }
        }

        @Test
        fun `should reject slug ending with hyphen`() {
            assertThrows<IllegalArgumentException> { ThemeId.of("abc-") }
        }

        @Test
        fun `should reject slug with consecutive hyphens`() {
            assertThrows<IllegalArgumentException> { ThemeId.of("abc--def") }
        }

        @Test
        fun `should reject slug with uppercase letters`() {
            assertThrows<IllegalArgumentException> { ThemeId.of("MyTheme") }
        }

        @Test
        fun `should reject slug with spaces`() {
            assertThrows<IllegalArgumentException> { ThemeId.of("my theme") }
        }

        @Test
        fun `should reject slug with underscores`() {
            assertThrows<IllegalArgumentException> { ThemeId.of("my_theme") }
        }

        @Test
        fun `should reject slug with special characters`() {
            assertThrows<IllegalArgumentException> { ThemeId.of("my.theme") }
            assertThrows<IllegalArgumentException> { ThemeId.of("my@theme") }
            assertThrows<IllegalArgumentException> { ThemeId.of("my!theme") }
        }

        @Test
        fun `should reject empty slug`() {
            assertThrows<IllegalArgumentException> { ThemeId.of("") }
        }

        @Test
        fun `validateOrNull should return null for invalid slug`() {
            assertNull(ThemeId.validateOrNull(""))
            assertNull(ThemeId.validateOrNull("ab"))
            assertNull(ThemeId.validateOrNull("ABC"))
            assertNull(ThemeId.validateOrNull("this-is-way-too-long-slug")) // 27 chars
        }
    }

    @Nested
    inner class ValueClassBehavior {
        @Test
        fun `toString should return the slug value`() {
            val id = ThemeId.of("my-theme")
            assertEquals("my-theme", id.toString())
        }

        @Test
        fun `value property should return the slug`() {
            val id = ThemeId.of("corporate")
            assertEquals("corporate", id.value)
        }

        @Test
        fun `equal slugs should be equal`() {
            val id1 = ThemeId.of("same-slug")
            val id2 = ThemeId.of("same-slug")
            assertEquals(id1, id2)
        }
    }
}
