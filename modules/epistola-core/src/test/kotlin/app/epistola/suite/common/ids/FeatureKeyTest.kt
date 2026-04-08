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

class FeatureKeyTest {

    @Nested
    inner class ValidKeys {
        @ParameterizedTest
        @ValueSource(
            strings = [
                "abc",
                "feedback",
                "feature1",
                "my-long-feature-name",
            ],
        )
        fun `should accept valid feature keys`(key: String) {
            assertDoesNotThrow { FeatureKey.of(key) }
        }

        @Test
        fun `should accept minimum length key`() {
            val key = FeatureKey.of("abc")
            assertEquals("abc", key.value)
        }

        @Test
        fun `should accept maximum length key`() {
            val key = FeatureKey.of("a" + "b".repeat(49))
            assertEquals(50, key.value.length)
        }

        @Test
        fun `validateOrNull should return FeatureKey for valid key`() {
            val result = FeatureKey.validateOrNull("feedback")
            assertNotNull(result)
            assertEquals("feedback", result.value)
        }
    }

    @Nested
    inner class InvalidKeys {
        @Test
        fun `should reject key shorter than 3 characters`() {
            val exception = assertThrows<IllegalArgumentException> { FeatureKey.of("ab") }
            assertEquals("Feature key must be 3-50 characters, got 2", exception.message)
        }

        @Test
        fun `should reject key longer than 50 characters`() {
            val key = "a" + "b".repeat(50)
            val exception = assertThrows<IllegalArgumentException> { FeatureKey.of(key) }
            assertEquals("Feature key must be 3-50 characters, got 51", exception.message)
        }

        @Test
        fun `should reject key starting with number`() {
            assertThrows<IllegalArgumentException> { FeatureKey.of("123abc") }
        }

        @Test
        fun `should reject key with uppercase letters`() {
            assertThrows<IllegalArgumentException> { FeatureKey.of("HtmlPreview") }
        }

        @Test
        fun `should reject key with consecutive hyphens`() {
            assertThrows<IllegalArgumentException> { FeatureKey.of("html--preview") }
        }

        @Test
        fun `should reject key ending with hyphen`() {
            assertThrows<IllegalArgumentException> { FeatureKey.of("html-") }
        }

        @Test
        fun `validateOrNull should return null for invalid key`() {
            assertNull(FeatureKey.validateOrNull(""))
            assertNull(FeatureKey.validateOrNull("ab"))
            assertNull(FeatureKey.validateOrNull("ABC"))
        }
    }

    @Nested
    inner class ValueClassBehavior {
        @Test
        fun `toString should return the key value`() {
            val key = FeatureKey.of("feedback")
            assertEquals("feedback", key.toString())
        }

        @Test
        fun `equal keys should be equal`() {
            val key1 = FeatureKey.of("feedback")
            val key2 = FeatureKey.of("feedback")
            assertEquals(key1, key2)
        }
    }
}
