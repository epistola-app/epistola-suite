package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val DEMO_CATALOG_URL = "classpath:epistola/catalogs/demo/catalog.json"

/**
 * Handler-level coverage for the subscriber upgrade endpoints (preferred over a
 * browser test per the deterministic-only UI-test philosophy): the preview
 * dialog renders, a no-change apply returns the done fragment, and a
 * cross-catalog conflict is surfaced in the dialog before Apply.
 */
class CatalogUpgradeHandlerTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun htmxGet() = HttpHeaders().apply { add("HX-Request", "true") }

    private fun htmxForm() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_FORM_URLENCODED
        add("HX-Request", "true")
    }

    @Test
    fun `upgrade-preview renders the no-change dialog for a freshly registered catalog`() = fixture {
        lateinit var testTenant: Tenant
        given {
            testTenant = tenant("Upgrade Preview Tenant")
            withMediator {
                RegisterCatalog(tenantKey = testTenant.id, sourceUrl = DEMO_CATALOG_URL, authType = AuthType.NONE).execute()
                InstallFromCatalog(tenantKey = testTenant.id, catalogKey = CatalogKey.of("epistola-demo")).execute()
            }
        }

        whenever {
            restTemplate.exchange(
                "/tenants/${testTenant.id}/catalogs/epistola-demo/upgrade-preview",
                org.springframework.http.HttpMethod.GET,
                HttpEntity<Void>(htmxGet()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("upgrade-dialog")
            assertThat(response.body).contains("Review catalog upgrade")
            assertThat(response.body).containsIgnoringCase("already up to date")
        }
    }

    @Test
    fun `apply upgrade with no changes returns the done fragment and no error`() = fixture {
        lateinit var testTenant: Tenant
        given {
            testTenant = tenant("Upgrade Apply Tenant")
            withMediator {
                RegisterCatalog(tenantKey = testTenant.id, sourceUrl = DEMO_CATALOG_URL, authType = AuthType.NONE).execute()
                InstallFromCatalog(tenantKey = testTenant.id, catalogKey = CatalogKey.of("epistola-demo")).execute()
            }
        }

        whenever {
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/catalogs/epistola-demo/upgrade",
                HttpEntity(org.springframework.util.LinkedMultiValueMap<String, String>(), htmxForm()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).doesNotContain("alert alert-danger")
        }
    }

    /**
     * Error path: previewing an AUTHORED catalog (no source URL) must not 500 —
     * the handler catches and re-renders the dialog with an error alert so the
     * user sees what went wrong. (Conflict-surfacing itself is covered
     * deterministically at the query level in `PreviewCatalogUpgradeTest`.)
     */
    @Test
    fun `upgrade-preview of a non-subscribed catalog re-renders the dialog with an error`() = fixture {
        lateinit var testTenant: Tenant
        given {
            testTenant = tenant("Upgrade Error Tenant")
            withMediator {
                CreateCatalog(tenantKey = testTenant.id, id = CatalogKey.of("authored-cat"), name = "Authored").execute()
            }
        }

        whenever {
            restTemplate.exchange(
                "/tenants/${testTenant.id}/catalogs/authored-cat/upgrade-preview",
                org.springframework.http.HttpMethod.GET,
                HttpEntity<Void>(htmxGet()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("upgrade-dialog")
            assertThat(response.body).contains("alert alert-danger")
            // No diff → no Apply button rendered.
            assertThat(response.body).doesNotContain("Apply upgrade")
        }
    }

    @Test
    fun `the catalog list shows an explicit check-for-updates control and does not auto-poll`() = fixture {
        lateinit var testTenant: Tenant
        given {
            testTenant = tenant("Upgrade List Explicit")
            withMediator {
                RegisterCatalog(testTenant.id, sourceUrl = DEMO_CATALOG_URL, authType = AuthType.NONE).execute()
            }
        }

        whenever {
            restTemplate.getForEntity("/tenants/${testTenant.id}/catalogs", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            // Explicit, user-driven: a "Check for updates" button is rendered
            // for the subscribed catalog and the indicator never auto-polls
            // the source on page load.
            assertThat(body).contains("Check for updates")
            assertThat(body).doesNotContain("hx-trigger=\"load\"")
        }
    }

    @Test
    fun `upgrade-check reports UP_TO_DATE for a freshly registered + installed catalog`() = fixture {
        lateinit var testTenant: Tenant
        given {
            testTenant = tenant("Upgrade Check Current")
            withMediator {
                RegisterCatalog(testTenant.id, sourceUrl = DEMO_CATALOG_URL, authType = AuthType.NONE).execute()
                InstallFromCatalog(tenantKey = testTenant.id, catalogKey = CatalogKey.of("epistola-demo")).execute()
            }
        }

        whenever {
            restTemplate.exchange(
                "/tenants/${testTenant.id}/catalogs/epistola-demo/upgrade-check",
                org.springframework.http.HttpMethod.GET,
                HttpEntity<Void>(htmxGet()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Up to date")
            assertThat(response.body).doesNotContain("Update →")
        }
    }

    @Test
    fun `upgrade-check reports out of sync when the source schema is older than this Epistola`() = fixture {
        lateinit var testTenant: Tenant
        given {
            testTenant = tenant("Upgrade Check Out Of Sync")
            // A subscribed source that publishes at an older catalog schema
            // version than this Epistola — the source must republish.
            val dir = Files.createTempDirectory("oos-catalog")
            val manifest = dir.resolve("catalog.json")
            Files.writeString(
                manifest,
                """{"schemaVersion":2,"catalog":{"slug":"old-source","name":"Old Source"},""" +
                    """"publisher":{"name":"P"},"release":{"version":"1.0.0"},"resources":[]}""",
            )
            withMediator {
                RegisterCatalog(testTenant.id, sourceUrl = manifest.toUri().toString(), authType = AuthType.NONE).execute()
            }
        }

        whenever {
            restTemplate.exchange(
                "/tenants/${testTenant.id}/catalogs/old-source/upgrade-check",
                org.springframework.http.HttpMethod.GET,
                HttpEntity<Void>(htmxGet()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("out of sync")
            assertThat(response.body).doesNotContain("Up to date")
        }
    }

    @Test
    fun `upgrade-check reports ZIP-managed for a ZIP-imported subscribed catalog (no source URL)`() = fixture {
        lateinit var consumer: Tenant
        given {
            // Produce a valid catalog ZIP from the demo…
            val publisher = tenant("Zip Mirror Publisher")
            lateinit var zip: ByteArray
            withMediator {
                RegisterCatalog(publisher.id, sourceUrl = DEMO_CATALOG_URL, authType = AuthType.NONE).execute()
                InstallFromCatalog(tenantKey = publisher.id, catalogKey = CatalogKey.of("epistola-demo")).execute()
                zip = ExportCatalogZip(tenantKey = publisher.id, catalogKey = CatalogKey.of("epistola-demo")).execute().zipBytes
            }
            // …then import it as SUBSCRIBED into a fresh tenant → a mirror with
            // no source URL.
            consumer = tenant("Zip Mirror Consumer")
            withMediator {
                ImportCatalogZip(tenantKey = consumer.id, zipBytes = zip, catalogType = CatalogType.SUBSCRIBED).execute()
            }
        }

        whenever {
            restTemplate.exchange(
                "/tenants/${consumer.id}/catalogs/epistola-demo/upgrade-check",
                org.springframework.http.HttpMethod.GET,
                HttpEntity<Void>(htmxGet()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("ZIP-managed")
        }
    }

    @Test
    fun `importing a too-old ZIP renders the inline schema-error fragment, not a server error`() = fixture {
        lateinit var testTenant: Tenant
        given { testTenant = tenant("Import Too Old") }

        whenever {
            // A minimal catalog ZIP at an older schema version → blocked by the import gate.
            val baos = ByteArrayOutputStream()
            ZipOutputStream(baos).use { zip ->
                zip.putNextEntry(ZipEntry("catalog.json"))
                zip.write(
                    (
                        """{"schemaVersion":2,"catalog":{"slug":"old-zip","name":"Old Zip"},""" +
                            """"publisher":{"name":"P"},"release":{"version":"1.0.0"},"resources":[]}"""
                        ).toByteArray(),
                )
                zip.closeEntry()
            }
            val payload = LinkedMultiValueMap<String, Any>()
            payload.add("catalogType", "AUTHORED")
            payload.add(
                "file",
                HttpEntity(
                    object : ByteArrayResource(baos.toByteArray()) {
                        override fun getFilename(): String = "old.zip"
                    },
                    HttpHeaders().apply { contentType = MediaType.parseMediaType("application/zip") },
                ),
            )
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/catalogs/import",
                HttpEntity(payload, HttpHeaders().apply { contentType = MediaType.MULTIPART_FORM_DATA }),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            // The blocked import must render the inline schema-error fragment (HTTP
            // 200, actionable message) — NOT crash with a TemplateInputException 500.
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Import blocked")
            assertThat(response.body).contains("too old")
        }
    }
}
