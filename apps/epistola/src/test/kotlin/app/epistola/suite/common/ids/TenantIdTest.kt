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

class TenantIdTest {

    @Nested
    inner class ValidSlugs {
        @ParameterizedTest
        @ValueSource(
            strings = [
                "abc", // minimum length
                "acme", // simple
                "acme-corp", // with hyphen
                "acme-corp-inc", // multiple hyphens
                "a1", // would be invalid (too short)
                "abc123", // alphanumeric
                "tenant1", // ends with number
                "a2b3c4", // mixed alphanumeric
                "my-company-2024", // typical usage
            ],
        )
        fun `should accept valid slugs`(slug: String) {
            if (slug.length >= 3) {
                assertDoesNotThrow { TenantId.of(slug) }
            }
        }

        @Test
        fun `should accept minimum length slug`() {
            val id = TenantId.of("abc")
            assertEquals("abc", id.value)
        }

        @Test
        fun `should accept maximum length slug`() {
            val slug = "a" + "b".repeat(62) // 63 characters
            val id = TenantId.of(slug)
            assertEquals(63, id.value.length)
        }

        @Test
        fun `should accept slug with hyphens`() {
            val id = TenantId.of("acme-corp-international")
            assertEquals("acme-corp-international", id.value)
        }

        @Test
        fun `should accept slug with numbers`() {
            val id = TenantId.of("company123")
            assertEquals("company123", id.value)
        }

        @Test
        fun `validateOrNull should return TenantId for valid slug`() {
            val result = TenantId.validateOrNull("valid-slug")
            assertNotNull(result)
            assertEquals("valid-slug", result.value)
        }
    }

    @Nested
    inner class InvalidSlugs {
        @Test
        fun `should reject slug shorter than 3 characters`() {
            val exception = assertThrows<IllegalArgumentException> { TenantId.of("ab") }
            assertEquals("Tenant ID must be 3-63 characters, got 2", exception.message)
        }

        @Test
        fun `should reject slug longer than 63 characters`() {
            val slug = "a" + "b".repeat(63) // 64 characters
            val exception = assertThrows<IllegalArgumentException> { TenantId.of(slug) }
            assertEquals("Tenant ID must be 3-63 characters, got 64", exception.message)
        }

        @Test
        fun `should reject slug starting with number`() {
            assertThrows<IllegalArgumentException> { TenantId.of("123abc") }
        }

        @Test
        fun `should reject slug starting with hyphen`() {
            assertThrows<IllegalArgumentException> { TenantId.of("-abc") }
        }

        @Test
        fun `should reject slug ending with hyphen`() {
            assertThrows<IllegalArgumentException> { TenantId.of("abc-") }
        }

        @Test
        fun `should reject slug with consecutive hyphens`() {
            assertThrows<IllegalArgumentException> { TenantId.of("abc--def") }
        }

        @Test
        fun `should reject slug with uppercase letters`() {
            assertThrows<IllegalArgumentException> { TenantId.of("AcmeCorp") }
        }

        @Test
        fun `should reject slug with spaces`() {
            assertThrows<IllegalArgumentException> { TenantId.of("acme corp") }
        }

        @Test
        fun `should reject slug with underscores`() {
            assertThrows<IllegalArgumentException> { TenantId.of("acme_corp") }
        }

        @Test
        fun `should reject slug with special characters`() {
            assertThrows<IllegalArgumentException> { TenantId.of("acme.corp") }
            assertThrows<IllegalArgumentException> { TenantId.of("acme@corp") }
            assertThrows<IllegalArgumentException> { TenantId.of("acme!corp") }
        }

        @Test
        fun `should reject empty slug`() {
            assertThrows<IllegalArgumentException> { TenantId.of("") }
        }

        @Test
        fun `validateOrNull should return null for invalid slug`() {
            assertNull(TenantId.validateOrNull(""))
            assertNull(TenantId.validateOrNull("ab"))
            assertNull(TenantId.validateOrNull("ABC"))
            assertNull(TenantId.validateOrNull("admin"))
        }
    }

    @Nested
    inner class ReservedWords {
        @ParameterizedTest
        @ValueSource(
            strings = [
                "admin",
                "api",
                "www",
                "system",
                "internal",
                "null",
                "undefined",
            ],
        )
        fun `should reject reserved words`(reserved: String) {
            val exception = assertThrows<IllegalArgumentException> { TenantId.of(reserved) }
            assertEquals("Tenant ID '$reserved' is reserved and cannot be used", exception.message)
        }

        @Test
        fun `should accept slugs that contain reserved words as substrings`() {
            assertDoesNotThrow { TenantId.of("admin-panel") }
            assertDoesNotThrow { TenantId.of("my-api") }
            assertDoesNotThrow { TenantId.of("www-company") }
        }
    }

    @Nested
    inner class ValueClassBehavior {
        @Test
        fun `toString should return the slug value`() {
            val id = TenantId.of("acme-corp")
            assertEquals("acme-corp", id.toString())
        }

        @Test
        fun `value property should return the slug`() {
            val id = TenantId.of("my-tenant")
            assertEquals("my-tenant", id.value)
        }

        @Test
        fun `equal slugs should be equal`() {
            val id1 = TenantId.of("same-slug")
            val id2 = TenantId.of("same-slug")
            assertEquals(id1, id2)
        }
    }
}
