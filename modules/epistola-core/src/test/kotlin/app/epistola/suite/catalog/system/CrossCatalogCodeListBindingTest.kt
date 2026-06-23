package app.epistola.suite.catalog.system

import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.queries.GetAttributeDefinition
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogImportContext
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import java.net.InetSocketAddress

/**
 * Cross-catalog code-list binding: an attribute authored in catalog A
 * references a code list in catalog B inside the same tenant. The bundled
 * `system` catalog is the production driver for this case (every tenant gets
 * `system/iso-639-1`, and other catalogs — including the demo catalog —
 * can bind to it without copying the entries).
 *
 * Covers:
 *  - direct write to the FK columns is honoured by `GetAttributeDefinition`,
 *  - export → re-import round-trip preserves the binding and emits a
 *    `DependencyRef.CodeList` on the manifest,
 *  - install from a real HTTP source — exercising the `RestClient` path,
 *    not just the in-process classpath/file shortcuts — also wires the
 *    cross-catalog FK correctly.
 */
@TestPropertySource(properties = ["epistola.catalog.allow-http=true"])
class CrossCatalogCodeListBindingTest : IntegrationTestBase() {

    @Test
    fun `attribute in catalog A binds to code list in catalog B (same tenant)`() {
        val tenant = createTenant("CrossBind1")
        val tenantId = TenantId(tenant.id)
        val systemCatalog = CatalogId(SYSTEM_CATALOG_KEY, tenantId)
        val authoredCatalog = CatalogKey.of("cross-bind-authored")

        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = authoredCatalog, name = "Cross Bind Authored").execute()
            val authoredId = CatalogId(authoredCatalog, tenantId)

            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("preferred-language"), authoredId),
                displayName = "Preferred Language",
                codeListId = CodeListId(CodeListKey.of("iso-639-1"), systemCatalog),
            ).execute()

            val fetched = GetAttributeDefinition(AttributeId(AttributeKey.of("preferred-language"), authoredId)).query()
            assertThat(fetched).isNotNull()
            assertThat(fetched!!.codeListCatalogKey?.value).isEqualTo(SYSTEM_CATALOG_KEY.value)
            assertThat(fetched.codeListSlug?.value).isEqualTo("iso-639-1")
            assertThat(fetched.allowedValues).isEmpty()
        }
    }

    @Test
    fun `export emits DependencyRef CodeList for cross-catalog bindings, re-import preserves them`() {
        val tenant = createTenant("CrossBind2")
        val tenantId = TenantId(tenant.id)
        val systemCatalog = CatalogId(SYSTEM_CATALOG_KEY, tenantId)
        val source = CatalogKey.of("xref-source")

        // Author a catalog containing one attribute bound across to `system`.
        val zip = withMediator {
            CreateCatalog(tenantKey = tenant.id, id = source, name = "Xref Source").execute()
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("locale-pref"), CatalogId(source, tenantId)),
                displayName = "Locale preference",
                codeListId = CodeListId(CodeListKey.of("bcp-47"), systemCatalog),
            ).execute()

            ExportCatalogZip(tenantKey = tenant.id, catalogKey = source).execute()
        }

        // Inspect the manifest + the attribute detail before re-import. The
        // dependency should be declared on the manifest, with the explicit
        // cross-catalog key carried through. The codeListBinding itself
        // lives on the resource detail inside the zip, not the manifest.
        val manifestJson = readZipEntry(zip.zipBytes, "catalog.json")
        assertThat(manifestJson).contains("\"schemaVersion\" : 5")
        assertThat(manifestJson).contains("\"type\" : \"codeList\"")
        assertThat(manifestJson).contains("\"catalogKey\" : \"system\"")
        assertThat(manifestJson).contains("\"slug\" : \"bcp-47\"")

        val attributeDetail = readZipEntry(zip.zipBytes, "resources/attribute/locale-pref.json")
        assertThat(attributeDetail).contains("\"codeListBinding\"")
        assertThat(attributeDetail).contains("\"catalogKey\" : \"system\"")

        // Re-import into a second tenant — system catalog is already there
        // (auto-installed on `createTenant`), so the cross-catalog FK lands
        // on the existing system row.
        val target = createTenant("CrossBind2-Target")
        val targetTenantId = TenantId(target.id)

        withMediator {
            val result = CatalogImportContext.runAsImport {
                ImportCatalogZip(
                    tenantKey = target.id,
                    zipBytes = zip.zipBytes,
                    catalogType = app.epistola.suite.catalog.CatalogType.AUTHORED,
                ).execute()
            }

            val fetched = GetAttributeDefinition(
                AttributeId(AttributeKey.of("locale-pref"), CatalogId(result.catalogKey, targetTenantId)),
            ).query()
            assertThat(fetched).isNotNull()
            assertThat(fetched!!.codeListCatalogKey?.value).isEqualTo("system")
            assertThat(fetched.codeListSlug?.value).isEqualTo("bcp-47")
        }
    }

    @Test
    fun `install from an HTTP catalog preserves cross-catalog code-list binding`() {
        // Serve a v3 manifest over real HTTP, exercising `RestClient` — the
        // production transport. classpath/file shortcuts in `CatalogClient`
        // would otherwise mask any bug in the HTTP fetch path.
        val tenant = createTenant("CrossBind3")
        val targetCatalogKey = CatalogKey.of("http-served")

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/catalog.json") { ex ->
                val body = manifestJson(targetCatalogKey.value).toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            server.createContext("/resources/attributes/lang.json") { ex ->
                val body = attributeDetailJson().toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            server.start()

            val baseUrl = "http://${server.address.hostString}:${server.address.port}"
            val manifestUrl = "$baseUrl/catalog.json"

            withMediator {
                CatalogImportContext.runAsImport {
                    val catalog = RegisterCatalog(
                        tenantKey = tenant.id,
                        sourceUrl = manifestUrl,
                        authType = AuthType.NONE,
                        authCredential = null,
                    ).execute()
                    InstallFromCatalog(tenantKey = tenant.id, catalogKey = catalog.id).execute()
                }

                val fetched = GetAttributeDefinition(
                    AttributeId(
                        AttributeKey.of("lang"),
                        CatalogId(targetCatalogKey, TenantId(tenant.id)),
                    ),
                ).query()
                assertThat(fetched).isNotNull()
                assertThat(fetched!!.codeListCatalogKey?.value).isEqualTo("system")
                assertThat(fetched.codeListSlug?.value).isEqualTo("iso-639-1")
            }
        } finally {
            server.stop(0)
        }
    }

    private fun readZipEntry(content: ByteArray, entryName: String): String {
        java.util.zip.ZipInputStream(content.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == entryName) return zis.readBytes().toString(Charsets.UTF_8)
                entry = zis.nextEntry
            }
        }
        error("$entryName not found in zip")
    }

    private fun manifestJson(slug: String): String = """
        {
          "schemaVersion": 3,
          "catalog": {
            "slug": "$slug",
            "name": "HTTP Served",
            "description": "Cross-catalog binding test catalog served over real HTTP"
          },
          "publisher": { "name": "Epistola tests" },
          "release": { "version": "1", "releasedAt": "2026-05-11T00:00:00Z" },
          "resources": [
            {
              "type": "attribute",
              "slug": "lang",
              "name": "Language",
              "detailUrl": "./resources/attributes/lang.json"
            }
          ],
          "dependencies": [
            { "type": "codeList", "catalogKey": "system", "slug": "iso-639-1" }
          ]
        }
    """.trimIndent()

    private fun attributeDetailJson(): String = """
        {
          "schemaVersion": 3,
          "resource": {
            "type": "attribute",
            "slug": "lang",
            "name": "Language",
            "codeListBinding": { "catalogKey": "system", "slug": "iso-639-1" }
          }
        }
    """.trimIndent()
}
