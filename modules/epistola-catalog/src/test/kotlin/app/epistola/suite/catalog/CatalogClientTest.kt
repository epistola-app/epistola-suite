package app.epistola.suite.catalog

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class CatalogClientTest {

    @Nested
    inner class ValidateUrl {

        @Test
        fun `accepts https json URL`() {
            assertDoesNotThrow {
                CatalogClient.validateUrl("https://example.com/catalog.json")
            }
        }

        @Test
        fun `accepts http json URL`() {
            assertDoesNotThrow {
                CatalogClient.validateUrl("http://example.com/catalog.json")
            }
        }

        @Test
        fun `accepts classpath URL`() {
            assertDoesNotThrow {
                CatalogClient.validateUrl("classpath:demo/catalog/catalog.json")
            }
        }

        @Test
        fun `accepts file URL`() {
            assertDoesNotThrow {
                CatalogClient.validateUrl("file:///tmp/catalog.json")
            }
        }

        @Test
        fun `rejects non-json URL`() {
            assertThrows<IllegalArgumentException> {
                CatalogClient.validateUrl("https://example.com/catalog.xml")
            }
        }

        @Test
        fun `rejects unsupported scheme`() {
            assertThrows<IllegalArgumentException> {
                CatalogClient.validateUrl("ftp://example.com/catalog.json")
            }
        }

        @Test
        fun `rejects file URL with path traversal`() {
            assertThrows<IllegalArgumentException> {
                CatalogClient.validateUrl("file:///tmp/../etc/passwd.json")
            }
        }
    }

    @Nested
    inner class ResolveDetailUrl {

        @Test
        fun `absolute https URL is returned as-is`() {
            val result = CatalogClient.resolveDetailUrl(
                "https://cdn.example.com/template.json",
                "https://example.com/catalog.json",
            )
            assert(result == "https://cdn.example.com/template.json")
        }

        @Test
        fun `relative URL is resolved against manifest URL`() {
            val result = CatalogClient.resolveDetailUrl(
                "./resources/templates/invoice.json",
                "https://example.com/catalog/catalog.json",
            )
            assert(result == "https://example.com/catalog/resources/templates/invoice.json")
        }

        @Test
        fun `relative URL resolved against classpath manifest`() {
            val result = CatalogClient.resolveDetailUrl(
                "./resources/templates/invoice.json",
                "classpath:demo/catalog/catalog.json",
            )
            assert(result == "classpath:demo/catalog/resources/templates/invoice.json")
        }

        @Test
        fun `absolute classpath URL is returned as-is`() {
            val result = CatalogClient.resolveDetailUrl(
                "classpath:other/template.json",
                "classpath:demo/catalog/catalog.json",
            )
            assert(result == "classpath:other/template.json")
        }

        @Test
        fun `absolute file URL is returned as-is`() {
            val result = CatalogClient.resolveDetailUrl(
                "file:///tmp/template.json",
                "https://example.com/catalog.json",
            )
            assert(result == "file:///tmp/template.json")
        }

        @Test
        fun `relative URL resolved against file manifest`() {
            val result = CatalogClient.resolveDetailUrl(
                "./resources/templates/invoice.json",
                "file:///tmp/catalog/catalog.json",
            )
            // URI.resolve produces file:/tmp/catalog/resources/templates/invoice.json (single slash after file:)
            assert(result.endsWith("/tmp/catalog/resources/templates/invoice.json")) {
                "Expected resolved path ending with /tmp/catalog/resources/templates/invoice.json but got: $result"
            }
            assert(result.startsWith("file:"))
        }
    }
}
