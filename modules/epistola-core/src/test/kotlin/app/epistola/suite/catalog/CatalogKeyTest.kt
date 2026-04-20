package app.epistola.suite.catalog

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CatalogKeyTest {

    @Test
    fun `valid slug is accepted`() {
        val key = CatalogKey.of("my-catalog")
        assertEquals("my-catalog", key.value)
    }

    @Test
    fun `minimum length slug is accepted`() {
        val key = CatalogKey.of("abc")
        assertEquals("abc", key.value)
    }

    @Test
    fun `maximum length slug is accepted`() {
        val key = CatalogKey.of("a".repeat(50))
        assertEquals(50, key.value.length)
    }

    @Test
    fun `slug with numbers is accepted`() {
        val key = CatalogKey.of("catalog-v2")
        assertEquals("catalog-v2", key.value)
    }

    @Test
    fun `too short slug is rejected`() {
        assertThrows<IllegalArgumentException> {
            CatalogKey.of("ab")
        }
    }

    @Test
    fun `too long slug is rejected`() {
        assertThrows<IllegalArgumentException> {
            CatalogKey.of("a".repeat(51))
        }
    }

    @Test
    fun `slug starting with number is rejected`() {
        assertThrows<IllegalArgumentException> {
            CatalogKey.of("1catalog")
        }
    }

    @Test
    fun `slug with uppercase is rejected`() {
        assertThrows<IllegalArgumentException> {
            CatalogKey.of("MyCatalog")
        }
    }

    @Test
    fun `slug with spaces is rejected`() {
        assertThrows<IllegalArgumentException> {
            CatalogKey.of("my catalog")
        }
    }

    @Test
    fun `slug ending with hyphen is rejected`() {
        assertThrows<IllegalArgumentException> {
            CatalogKey.of("catalog-")
        }
    }

    @Test
    fun `slug with consecutive hyphens is rejected`() {
        assertThrows<IllegalArgumentException> {
            CatalogKey.of("my--catalog")
        }
    }

    @Test
    fun `validateOrNull returns key for valid slug`() {
        assertNotNull(CatalogKey.validateOrNull("valid-slug"))
    }

    @Test
    fun `validateOrNull returns null for invalid slug`() {
        assertNull(CatalogKey.validateOrNull(""))
    }
}
