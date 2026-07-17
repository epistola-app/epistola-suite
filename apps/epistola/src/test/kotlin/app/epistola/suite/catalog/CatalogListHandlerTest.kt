package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.CreateStencilVersion
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

/**
 * The catalog list mutates through a single always-present `#catalog-list`
 * swap region (regression for the broken table layout: an empty→first-catalog
 * create previously had no swap target and returned a bare `<tbody>` the
 * browser fragment parser relocated). Every list mutation must return that
 * wrapper containing *either* the table *or* the empty state.
 */
class CatalogListHandlerTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun htmxForm() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_FORM_URLENCODED
        add("HX-Request", "true")
    }

    @Test
    fun `HTMX GET new returns the dialog fragment`() = fixture {
        lateinit var t: Tenant
        given { t = tenant("Catalog New Dialog") }

        whenever {
            restTemplate.exchange(
                "/tenants/${t.id}/catalogs/new",
                HttpMethod.GET,
                HttpEntity<Void>(HttpHeaders().apply { add("HX-Request", "true") }),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            // The dialog-shell chrome + the caller-owned form with its fields.
            assertThat(body).contains("id=\"create-catalog-dialog\"")
            assertThat(body).contains("id=\"create-catalog-form\"")
            assertThat(body).contains("name=\"name\"")
            assertThat(body).contains("name=\"slug\"")
            // It is a fragment, not the whole page (no app shell).
            assertThat(body).doesNotContain("<html")
        }
    }

    @Test
    fun `HTMX POST create invalid retargets the form with 422`() = fixture {
        lateinit var t: Tenant
        given { t = tenant("Catalog Create Invalid") }

        whenever {
            val payload = LinkedMultiValueMap<String, String>()
            payload.add("slug", "BAD SLUG") // uppercase + space → fails asCatalogId
            payload.add("name", "Bad Slug Cat")
            restTemplate.postForEntity(
                "/tenants/${t.id}/catalogs/create",
                HttpEntity(payload, htmxForm()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
            // Retargets the FORM (not the dialog, not the list) so the open modal
            // dialog is never removed from the top layer.
            assertThat(response.headers.getFirst("HX-Retarget")).isEqualTo("#create-catalog-form")
            assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("outerHTML")
            val body = response.body!!
            assertThat(body).contains("id=\"create-catalog-form\"")
            assertThat(body).contains("form-error")
        }
    }

    @Test
    fun `HTMX POST create valid closes the dialog and OOB-refreshes the list`() = fixture {
        lateinit var t: Tenant
        given { t = tenant("Catalog Create Valid") }

        whenever {
            val payload = LinkedMultiValueMap<String, String>()
            payload.add("slug", "my-catalog")
            payload.add("name", "My Catalog")
            restTemplate.postForEntity(
                "/tenants/${t.id}/catalogs/create",
                HttpEntity(payload, htmxForm()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // Stay-on-list: close the dialog + OOB-refresh the list region.
            assertThat(response.headers.getFirst("HX-Trigger")).isEqualTo("closeDialog")
            assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("none")
            val body = response.body!!
            assertThat(body).contains("id=\"catalog-list\"")
            assertThat(body).contains("hx-swap-oob")
            assertThat(body).contains("My Catalog")
            // Persistence verified through the mediator.
            val persisted = withMediator { ListCatalogs(tenantKey = t.id).query() }
            assertThat(persisted.map { it.name }).contains("My Catalog")
        }
    }

    @Test
    fun `HTMX POST create duplicate slug returns 422 with inline error`() = fixture {
        lateinit var t: Tenant
        given {
            t = tenant("Catalog Create Duplicate")
            withMediator {
                CreateCatalog(tenantKey = t.id, id = CatalogKey.of("dup"), name = "Dup Cat").execute()
            }
        }

        whenever {
            val payload = LinkedMultiValueMap<String, String>()
            payload.add("slug", "dup")
            payload.add("name", "Second Dup")
            restTemplate.postForEntity(
                "/tenants/${t.id}/catalogs/create",
                HttpEntity(payload, htmxForm()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
            assertThat(response.headers.getFirst("HX-Retarget")).isEqualTo("#create-catalog-form")
            val body = response.body!!
            assertThat(body).contains("A catalog with this ID already exists")
        }
    }

    @Test
    fun `non-HTMX GET new renders the list page with the dialog embedded and open`() = fixture {
        lateinit var t: Tenant
        given { t = tenant("Catalog New DirectNav") }

        whenever {
            restTemplate.getForEntity("/tenants/${t.id}/catalogs/new", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            // Full host page (app shell) with the dialog embedded in the mount.
            assertThat(body).contains("<html")
            assertThat(body).contains("id=\"dialog-mount\"")
            assertThat(body).contains("id=\"create-catalog-dialog\"")
        }
    }

    @Test
    fun `HTMX GET register returns the dialog fragment`() = fixture {
        lateinit var t: Tenant
        given { t = tenant("Catalog Subscribe Dialog") }

        whenever {
            restTemplate.exchange(
                "/tenants/${t.id}/catalogs/subscribe",
                HttpMethod.GET,
                HttpEntity<Void>(HttpHeaders().apply { add("HX-Request", "true") }),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            // The dialog-shell chrome + the caller-owned form with its fields.
            assertThat(body).contains("id=\"subscribe-catalog-dialog\"")
            assertThat(body).contains("id=\"subscribe-catalog-form\"")
            assertThat(body).contains("name=\"sourceUrl\"")
            assertThat(body).contains("name=\"authType\"")
            // It is a fragment, not the whole page (no app shell).
            assertThat(body).doesNotContain("<html")
        }
    }

    @Test
    fun `HTMX POST register invalid URL shows the form error and keeps the form`() = fixture {
        lateinit var t: Tenant
        given { t = tenant("Catalog Subscribe Invalid") }

        whenever {
            val payload = LinkedMultiValueMap<String, String>()
            // A classpath manifest that does not exist → RegisterCatalog throws.
            payload.add("sourceUrl", "classpath:epistola/catalogs/does-not-exist/catalog.json")
            restTemplate.postForEntity(
                "/tenants/${t.id}/catalogs/subscribe",
                HttpEntity(payload, htmxForm()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
            // The OOB error slot handles the swap — no retarget, primary swap disabled —
            // so the live form (URL, auth type, credential toggle) is left untouched.
            assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("none")
            assertThat(response.headers.getFirst("HX-Retarget")).isNull()
            val body = response.body!!
            assertThat(body).contains("id=\"subscribe-catalog-error\"")
            assertThat(body).contains("Failed to register catalog")
        }
    }

    @Test
    fun `HTMX POST register valid closes the dialog and OOB-refreshes the list`() = fixture {
        lateinit var t: Tenant
        given { t = tenant("Catalog Subscribe Valid") }

        whenever {
            val payload = LinkedMultiValueMap<String, String>()
            payload.add("sourceUrl", "classpath:epistola/catalogs/demo/catalog.json")
            restTemplate.postForEntity(
                "/tenants/${t.id}/catalogs/subscribe",
                HttpEntity(payload, htmxForm()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // Stay-on-list: close the dialog + OOB-refresh the list region.
            assertThat(response.headers.getFirst("HX-Trigger")).isEqualTo("closeDialog")
            assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("none")
            val body = response.body!!
            assertThat(body).contains("id=\"catalog-list\"")
            assertThat(body).contains("hx-swap-oob")
            assertThat(body).contains("Epistola Demo Catalog")
            // Persistence verified through the mediator.
            val persisted = withMediator { ListCatalogs(tenantKey = t.id).query() }
            assertThat(persisted.map { it.name }).contains("Epistola Demo Catalog")
        }
    }

    @Test
    fun `non-HTMX GET register renders the list page with the dialog embedded and open`() = fixture {
        lateinit var t: Tenant
        given { t = tenant("Catalog Subscribe DirectNav") }

        whenever {
            restTemplate.getForEntity("/tenants/${t.id}/catalogs/subscribe", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            // Full host page (app shell) with the dialog embedded in the mount.
            assertThat(body).contains("<html")
            assertThat(body).contains("id=\"dialog-mount\"")
            assertThat(body).contains("id=\"subscribe-catalog-dialog\"")
        }
    }

    @Test
    fun `plain list route does not embed the create dialog`() = fixture {
        lateinit var t: Tenant
        given { t = tenant("Catalog Plain List") }

        whenever {
            restTemplate.getForEntity("/tenants/${t.id}/catalogs", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            // The mount is present but empty on the plain list route (openDialog is
            // unset, so the th:if guard must wrap the th:replace). See docs/dialog-forms.md.
            assertThat(body).contains("id=\"dialog-mount\"")
            assertThat(body).doesNotContain("id=\"create-catalog-dialog\"")
        }
    }

    @Test
    fun `removing a catalog returns the whole #catalog-list region (parser-safe, not a bare tbody)`() = fixture {
        lateinit var t: Tenant
        given {
            t = tenant("Catalog List Remove")
            listOf("keep-cat" to "Keep Cat", "gone-cat" to "Gone Cat").forEach { (slug, name) ->
                val p = LinkedMultiValueMap<String, String>()
                p.add("slug", slug)
                p.add("name", name)
                restTemplate.postForEntity("/tenants/${t.id}/catalogs/create", HttpEntity(p, htmxForm()), String::class.java)
            }
        }

        whenever {
            restTemplate.exchange(
                "/tenants/${t.id}/catalogs/gone-cat/delete",
                HttpMethod.POST,
                HttpEntity<Void>(HttpHeaders().apply { add("HX-Request", "true") }),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            // The unregister response is the full stable region containing a
            // complete <table> (not a bare <tbody> the browser would relocate),
            // with the removed catalog gone and the survivor still listed.
            assertThat(body).contains("id=\"catalog-list\"")
            assertThat(body).contains("<table").contains("</table>")
            assertThat(body).contains("Keep Cat")
            assertThat(body).doesNotContain("Gone Cat")
        }
    }

    @Test
    fun `a drifted AUTHORED catalog shows 'pending changes' in the Version column - a clean one does not`() = fixture {
        lateinit var t: Tenant
        given {
            t = tenant("Catalog List Drift")
            withMediator {
                // Clean: released, untouched.
                val clean = CatalogKey.of("clean-cat")
                CreateCatalog(tenantKey = t.id, id = clean, name = "Clean Cat").execute()
                CreateTheme(id = ThemeId(ThemeKey.of("ct1"), CatalogId(clean, TenantId(t.id))), name = "C1").execute()
                ReleaseCatalogVersion(tenantKey = t.id, catalogKey = clean, version = "1.0.0").execute()

                // Drifted: released, then edited.
                val drift = CatalogKey.of("drift-cat")
                CreateCatalog(tenantKey = t.id, id = drift, name = "Drift Cat").execute()
                CreateTheme(id = ThemeId(ThemeKey.of("dt1"), CatalogId(drift, TenantId(t.id))), name = "D1").execute()
                ReleaseCatalogVersion(tenantKey = t.id, catalogKey = drift, version = "2.0.0").execute()
                CreateTheme(id = ThemeId(ThemeKey.of("dt2"), CatalogId(drift, TenantId(t.id))), name = "D2 (post-release)").execute()
            }
        }

        whenever {
            restTemplate.getForEntity("/tenants/${t.id}/catalogs", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            // Drifted catalog: version + the pending-changes hint with the
            // "exports as v2.0.0-dev" title.
            assertThat(body).contains("pending changes")
            assertThat(body).contains("v2.0.0-dev")
            // Clean catalog's row carries no pending hint (only the drifted one).
            assertThat(body.split("pending changes")).hasSize(2) // exactly one occurrence
        }
    }

    @Test
    fun `export-check dialog names the templates that pin an outdated stencil version`() = fixture {
        lateinit var t: Tenant
        given {
            t = tenant("Export Conflict Dialog")
            withMediator {
                val catalogKey = CatalogKey.of("conflict-cat")
                val catalogId = CatalogId(catalogKey, TenantId(t.id))
                CreateCatalog(tenantKey = t.id, id = catalogKey, name = "Conflict Cat").execute()

                // Stencil with two published versions (latest is v2).
                val stencilKey = StencilKey.of("shared-stencil")
                val stencilId = StencilId(stencilKey, catalogId)
                CreateStencil(id = stencilId, name = "Shared Stencil", content = stencilContent()).execute()
                PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(1), stencilId)).execute()
                CreateStencilVersion(stencilId = stencilId, content = stencilContent()).execute()
                PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(2), stencilId)).execute()

                // "Letter A" pins the stale v1; "Letter B" is already on the latest v2.
                publishTemplatePinningStencil(catalogId, "letter-a", "Letter A", stencilKey, 1)
                publishTemplatePinningStencil(catalogId, "letter-b", "Letter B", stencilKey, 2)
            }
        }

        whenever {
            restTemplate.exchange(
                "/tenants/${t.id}/catalogs/conflict-cat/export-check",
                HttpMethod.GET,
                HttpEntity<Void>(HttpHeaders().apply { add("HX-Request", "true") }),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            // The dialog names the offending template + its stale pin, and the
            // stencil's latest version. It must NOT list "Letter B" (already on v2).
            assertThat(body).contains("Templates to update")
            assertThat(body).contains("Letter A")
            assertThat(body).contains("pins")
            assertThat(body).contains("v1")
            assertThat(body).contains("v2")
            assertThat(body).doesNotContain("Letter B")
            // And it tells the user to publish after upgrading.
            assertThat(body).contains("publish")
        }
    }

    @Test
    fun `HTMX GET import returns the dialog fragment`() = fixture {
        lateinit var t: Tenant
        given { t = tenant("Catalog Import Dialog") }

        whenever {
            restTemplate.exchange(
                "/tenants/${t.id}/catalogs/import",
                HttpMethod.GET,
                HttpEntity<Void>(HttpHeaders().apply { add("HX-Request", "true") }),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            // The dialog-shell chrome + the caller-owned multipart form with its fields.
            assertThat(body).contains("id=\"import-catalog-dialog\"")
            assertThat(body).contains("id=\"import-catalog-form\"")
            assertThat(body).contains("type=\"file\"")
            assertThat(body).contains("name=\"catalogType\"")
            // It is a fragment, not the whole page (no app shell).
            assertThat(body).doesNotContain("<html")
        }
    }

    @Test
    fun `HTMX POST import with no file shows the form error`() = fixture {
        lateinit var t: Tenant
        given { t = tenant("Catalog Import No File") }

        whenever {
            // Multipart POST with a catalogType part but NO file part.
            val payload = LinkedMultiValueMap<String, Any>()
            payload.add("catalogType", "AUTHORED")
            val headers = HttpHeaders().apply {
                contentType = MediaType.MULTIPART_FORM_DATA
                add("HX-Request", "true")
            }
            restTemplate.postForEntity(
                "/tenants/${t.id}/catalogs/import",
                HttpEntity(payload, headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            // Shaped global form error (HtmxDsl.globalFormError): the body/file input
            // is NOT re-rendered — only the global error slot is OOB-swapped.
            assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
            assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("none")
            val body = response.body!!
            assertThat(body).contains("No file provided")
            assertThat(body).contains("id=\"import-catalog-error\"")
        }
    }

    @Test
    fun `non-HTMX GET import renders the list page with the dialog embedded and open`() = fixture {
        lateinit var t: Tenant
        given { t = tenant("Catalog Import DirectNav") }

        whenever {
            restTemplate.getForEntity("/tenants/${t.id}/catalogs/import", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            // Full host page (app shell) with the dialog embedded in the mount.
            assertThat(body).contains("<html")
            assertThat(body).contains("id=\"dialog-mount\"")
            assertThat(body).contains("id=\"import-catalog-dialog\"")
        }
    }

    @Test
    fun `HTMX POST import valid redirects to the imported catalog's browse page`() = fixture {
        lateinit var consumer: Tenant
        lateinit var zipHolder: ByteArray
        given {
            // Export the demo catalog from a publisher tenant to get a real ZIP…
            val publisher = tenant("Catalog Import Publisher")
            withMediator {
                RegisterCatalog(publisher.id, sourceUrl = "classpath:epistola/catalogs/demo/catalog.json", authType = AuthType.NONE).execute()
                InstallFromCatalog(tenantKey = publisher.id, catalogKey = CatalogKey.of("epistola-demo")).execute()
                zipHolder = ExportCatalogZip(tenantKey = publisher.id, catalogKey = CatalogKey.of("epistola-demo")).execute().zipBytes
            }
            // …then import it into a fresh consumer tenant via the UI endpoint.
            consumer = tenant("Catalog Import Consumer")
        }

        whenever {
            val payload = LinkedMultiValueMap<String, Any>()
            payload.add("catalogType", "SUBSCRIBED")
            payload.add(
                "file",
                HttpEntity(
                    object : ByteArrayResource(zipHolder) {
                        override fun getFilename(): String = "demo.zip"
                    },
                    HttpHeaders().apply { contentType = MediaType.parseMediaType("application/zip") },
                ),
            )
            restTemplate.postForEntity(
                "/tenants/${consumer.id}/catalogs/import",
                HttpEntity(
                    payload,
                    HttpHeaders().apply {
                        contentType = MediaType.MULTIPART_FORM_DATA
                        add("HX-Request", "true")
                    },
                ),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            // Success = HX-Redirect to the imported catalog's browse page; the
            // dialog disappears with the navigation.
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.headers.getFirst("HX-Redirect"))
                .isEqualTo("/tenants/${consumer.id}/catalogs/epistola-demo/browse")
        }
    }

    private fun publishTemplatePinningStencil(
        catalogId: CatalogId,
        slug: String,
        name: String,
        stencilKey: StencilKey,
        stencilVersion: Int,
    ) {
        val templateId = TemplateId(TemplateKey.of(slug), catalogId)
        CreateDocumentTemplate(id = templateId, name = name).execute()
        val variantId = VariantId(VariantKey.INITIAL, templateId)
        UpdateDraft(
            variantId = variantId,
            templateModel = templateEmbeddingStencil(stencilKey, stencilVersion),
        ).execute()
        PublishVersion(versionId = VersionId(VersionKey.of(1), variantId)).execute()
    }

    private fun stencilContent(): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf("root" to Node(id = "root", type = "root", slots = listOf("root-slot"))),
        slots = mapOf("root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = emptyList())),
        themeRef = ThemeRef.Inherit,
    )

    private fun templateEmbeddingStencil(stencilKey: StencilKey, version: Int): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "stencil-instance" to Node(
                id = "stencil-instance",
                type = "stencil",
                slots = listOf("stencil-children"),
                props = mapOf("stencilId" to stencilKey.value, "version" to version),
            ),
        ),
        slots = mapOf(
            "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("stencil-instance")),
            "stencil-children" to Slot(id = "stencil-children", nodeId = "stencil-instance", name = "children", children = emptyList()),
        ),
        themeRef = ThemeRef.Inherit,
    )
}
