// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.catalog.migrations.CatalogSchemaMigrator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader
import org.springframework.web.client.RestClient
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.writeBytes

private const val FIXTURE_MANIFEST = "classpath:epistola/catalogs/fixture/catalog.json"

class CatalogClientTest {
    /**
     * A classpath source is read once per JVM and served from the cache afterwards; a file source
     * is read on every call, because it can change underneath a running process; a missing
     * classpath resource fails every time and caches nothing.
     */
    @Nested
    inner class ClasspathCache {
        private val reads = AtomicInteger()
        private val countingLoader = object : ResourceLoader {
            private val delegate = DefaultResourceLoader()

            override fun getResource(location: String): Resource {
                reads.incrementAndGet()
                return delegate.getResource(location)
            }

            override fun getClassLoader(): ClassLoader? = delegate.classLoader
        }
        private val client = CatalogClient(
            catalogRestClient = RestClient.create(),
            resourceLoader = countingLoader,
            schemaMigrator = CatalogSchemaMigrator(),
        )

        @Test
        fun `a classpath manifest is read once`() {
            val first = client.fetchManifest(FIXTURE_MANIFEST, AuthType.NONE, null)
            val second = client.fetchManifest(FIXTURE_MANIFEST, AuthType.NONE, null)

            assertThat(reads.get()).isEqualTo(1)
            assertThat(second).isSameAs(first)
        }

        @Test
        fun `a classpath binary is read once and handed out as a copy`() {
            val manifest = client.fetchManifest(FIXTURE_MANIFEST, AuthType.NONE, null)
            val detailUrl = manifest.resources.first().detailUrl
            reads.set(0)

            val first = client.fetchBinaryContent(detailUrl, FIXTURE_MANIFEST, AuthType.NONE, null)
            val second = client.fetchBinaryContent(detailUrl, FIXTURE_MANIFEST, AuthType.NONE, null)

            assertThat(reads.get()).isEqualTo(1)
            assertThat(second).isEqualTo(first).isNotSameAs(first)
        }

        @Test
        fun `a file manifest is read on every call`(
            @TempDir dir: Path,
        ) {
            val source = DefaultResourceLoader().getResource(FIXTURE_MANIFEST).contentAsByteArray
            val file = dir.resolve("catalog.json").also { it.writeBytes(source) }
            val url = file.toUri().toString()

            val first = client.fetchManifest(url, AuthType.NONE, null)
            file.writeBytes(String(source).replace(first.catalog.name, "Renamed").toByteArray())
            val second = client.fetchManifest(url, AuthType.NONE, null)

            assertThat(second.catalog.name).isEqualTo("Renamed")
        }

        @Test
        fun `a missing classpath resource fails every time and caches nothing`() {
            repeat(2) {
                assertThrows<CatalogFetchException> {
                    client.fetchManifest("classpath:epistola/catalogs/nowhere/catalog.json", AuthType.NONE, null)
                }
            }

            assertThat(reads.get()).isEqualTo(2)
        }
    }

    @Nested
    inner class ValidateUrl {

        @Test
        fun `accepts https json URL`() {
            assertDoesNotThrow {
                CatalogClient.validateUrl("https://example.com/catalog.json")
            }
        }

        @Test
        fun `rejects http json URL by default`() {
            assertThrows<IllegalArgumentException> {
                CatalogClient.validateUrl("http://example.com/catalog.json")
            }
        }

        @Test
        fun `accepts http json URL when explicitly allowed`() {
            assertDoesNotThrow {
                CatalogClient.validateUrl("http://example.com/catalog.json", setOf("http", "https", "file", "classpath"))
            }
        }

        @Test
        fun `accepts classpath URL`() {
            assertDoesNotThrow {
                CatalogClient.validateUrl("classpath:epistola/catalogs/fixture/catalog.json")
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
                "classpath:epistola/catalogs/fixture/catalog.json",
            )
            assert(result == "classpath:epistola/catalogs/fixture/resources/templates/invoice.json")
        }

        @Test
        fun `absolute classpath URL is returned as-is`() {
            val result = CatalogClient.resolveDetailUrl(
                "classpath:other/template.json",
                "classpath:epistola/catalogs/fixture/catalog.json",
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
