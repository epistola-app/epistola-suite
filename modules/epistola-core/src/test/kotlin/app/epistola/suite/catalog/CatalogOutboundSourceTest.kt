// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.assets.queries.ListAssets
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.catalog.migrations.CatalogSchemaUnknownException
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/**
 * A served catalog may only make Epistola read what the outbound source rules allow, however its
 * manifest names it. Before these rules an image's `contentUrl` could name a local file or an
 * internal address; the bytes were stored as a tenant asset and could be downloaded back.
 *
 * The catalog is served from a loopback fake, which the test profile allows
 * (`allow-private-networks`); link-local addresses and scheme changes stay refused regardless.
 */
class CatalogOutboundSourceTest : IntegrationTestBase() {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    /** What the image detail names as its content. Swapped between registering and installing. */
    @Volatile
    private var imageContentUrl = "./logo.svg"

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = when (exchange.requestURI.path) {
                "/catalog.json" -> MANIFEST
                "/resources/logo.json" -> imageDetail(imageContentUrl)
                "/logo.svg" -> SVG
                "/redirect-to-metadata.svg" -> null
                else -> null
            }
            if (exchange.requestURI.path == "/redirect-to-metadata.svg") {
                exchange.responseHeaders.add("Location", "http://169.254.169.254/latest/meta-data/")
                exchange.sendResponseHeaders(302, -1)
            } else if (body == null) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
            exchange.close()
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `a manifest whose image content is a local file is refused at registration`() {
        imageContentUrl = "file:///etc/passwd"
        val tenant = createTenant("Outbound File")

        withMediator {
            assertThatThrownBy {
                RegisterCatalog(tenantKey = tenant.id, sourceUrl = "$baseUrl/catalog.json").execute()
            }.hasMessageContaining("may only name paths relative to its manifest")
            assertThat(ListCatalogs(tenant.id).query().map { it.id.value }).doesNotContain(CATALOG_SLUG)
        }
    }

    // From wire v8 a resource detail may only name its content by a path relative to the manifest,
    // so a detail that turns into an absolute or non-http location is refused by the wire format
    // itself: the whole install is rejected, as any detail that fails the wire gate is.

    @Test
    fun `an image whose content turns into a local file fails to install and stores nothing`() {
        assertInstallRejected("file:///etc/passwd", "Outbound File Install")
    }

    @Test
    fun `an image whose content turns into a link-local address fails to install and stores nothing`() {
        assertInstallRejected("http://169.254.169.254/latest/meta-data/iam/security-credentials/", "Outbound Metadata Install")
    }

    /** A relative path the wire format accepts, served by a redirect the outbound rules refuse. */
    @Test
    fun `an image whose content redirects to a link-local address fails to install and stores nothing`() {
        assertInstallRefused("./redirect-to-metadata.svg", "Outbound Redirect Install")
    }

    @Test
    fun `an image whose content turns into a classpath resource fails to install and stores nothing`() {
        assertInstallRejected("classpath:epistola/catalogs/system/catalog.json", "Outbound Classpath Install")
    }

    @Test
    fun `a benign served catalog still installs its image`() {
        val tenant = createTenant("Outbound Benign")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = "$baseUrl/catalog.json").execute()
            val results = InstallFromCatalog(catalog = testCatalog(tenant.id, CatalogKey.of(CATALOG_SLUG))).execute()

            assertThat(results).allMatch { it.status != InstallStatus.FAILED }
            assertThat(ListAssets(tenant.id, catalog = testCatalog(tenant.id, CatalogKey.of(CATALOG_SLUG))).query()).hasSize(1)
        }
    }

    @Test
    fun `registering a link-local or non-bundled classpath source is a validation error`() {
        val tenant = createTenant("Outbound Register")

        withMediator {
            listOf(
                "http://169.254.169.254/latest/catalog.json",
                "https://[::ffff:169.254.169.254]/catalog.json",
                "file:///etc/passwd.json",
                "classpath:application.json",
                "classpath:epistola/catalogs/../../application.json",
            ).forEach { url ->
                assertThatThrownBy { RegisterCatalog(tenantKey = tenant.id, sourceUrl = url).execute() }
                    .describedAs(url)
                    .isInstanceOfSatisfying(ValidationException::class.java) {
                        assertThat(it.field).isEqualTo("sourceUrl")
                        assertThat(it.code).isEqualTo(ValidationCode.SOURCE_URL_NOT_ALLOWED)
                    }
            }
            assertThat(ListCatalogs(tenant.id).query().map { it.id.value }).doesNotContain(CATALOG_SLUG)
        }
    }

    /** Register while the catalog is benign, then make the image point at [contentUrl] and install. */
    private fun assertInstallRefused(contentUrl: String, tenantName: String) {
        val tenant = createTenant(tenantName)

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = "$baseUrl/catalog.json").execute()
            imageContentUrl = contentUrl

            val results = InstallFromCatalog(catalog = testCatalog(tenant.id, CatalogKey.of(CATALOG_SLUG))).execute()

            assertThat(results).anyMatch { it.slug == IMAGE_SLUG && it.status == InstallStatus.FAILED }
            assertThat(ListAssets(tenant.id, catalog = testCatalog(tenant.id, CatalogKey.of(CATALOG_SLUG))).query()).isEmpty()
        }
    }

    /**
     * Register while the catalog is benign, then make the image point at [contentUrl], which the
     * wire format refuses: the install throws and nothing is stored.
     */
    private fun assertInstallRejected(contentUrl: String, tenantName: String) {
        val tenant = createTenant(tenantName)

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = "$baseUrl/catalog.json").execute()
            imageContentUrl = contentUrl

            assertThatThrownBy {
                InstallFromCatalog(catalog = testCatalog(tenant.id, CatalogKey.of(CATALOG_SLUG))).execute()
            }
                .isInstanceOf(CatalogSchemaUnknownException::class.java)
                .hasMessageContaining("must be a relative path below the manifest's directory")
            assertThat(ListAssets(tenant.id, catalog = testCatalog(tenant.id, CatalogKey.of(CATALOG_SLUG))).query()).isEmpty()
        }
    }

    private fun imageDetail(contentUrl: String) = """
        {
          "schemaVersion": $CATALOG_SCHEMA_VERSION,
          "resource": {
            "type": "image",
            "key": "$IMAGE_SLUG",
            "name": "Logo",
            "mediaType": "image/svg+xml",
            "width": null,
            "height": null,
            "contentUrl": "$contentUrl",
            "contentHash": "$SVG_SHA256"
          }
        }
    """.trimIndent()

    private companion object {
        const val CATALOG_SLUG = "outbound-test"
        const val IMAGE_SLUG = "01966a00-0000-7000-8000-0000000000aa"

        val MANIFEST = """
            {
              "schemaVersion": $CATALOG_SCHEMA_VERSION,
              "catalog": { "key": "$CATALOG_SLUG", "registry": "exchange.epistola.app", "namespace": "outbound-test", "name": "Outbound Test Catalog" },
              "publisher": { "name": "Test" },
              "release": { "version": "1.0.0" },
              "resources": [
                { "type": "image", "key": "$IMAGE_SLUG", "name": "Logo", "detailUrl": "./resources/logo.json" }
              ]
            }
        """.trimIndent()

        const val SVG = """<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1"/>"""

        val SVG_SHA256: String = java.security.MessageDigest.getInstance("SHA-256").digest(SVG.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
