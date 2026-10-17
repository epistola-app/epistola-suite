// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.UpdateStencil
import app.epistola.suite.stencils.queries.GetStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
/**
 * The stencil pages and the editor's stencil routes, on the 2.0 model: a stencil has one working
 * copy (no versions), the editor reads and saves it as JSON (`/content`), the detail page marks it
 * ready and toggles whether it reads the template's data, and the usage list says per variant
 * whether it still fits the stencil as it is.
 */
class StencilHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `JSON GET content returns the working copy`() = fixture {
        lateinit var seeded: Seeded

        given { seeded = seedStencil("Content Read", content = stencilV1()) }

        whenever {
            val headers = HttpHeaders().apply { accept = listOf(MediaType.APPLICATION_JSON) }
            restTemplate.exchange(
                "/tenants/${seeded.tenantId.key}/stencils/${seeded.catalogKey}/${seeded.stencilKey}/content",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("\"stencilId\":\"${seeded.stencilKey}\"")
            assertThat(response.body).contains("\"editable\":true")
            assertThat(response.body).contains("ph-body")
        }
    }

    @Test
    fun `JSON PUT content saves the working copy`() = fixture {
        lateinit var seeded: Seeded

        given { seeded = seedStencil("Content Save") }

        whenever {
            val headers = HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                accept = listOf(MediaType.APPLICATION_JSON)
            }
            restTemplate.exchange(
                "/tenants/${seeded.tenantId.key}/stencils/${seeded.catalogKey}/${seeded.stencilKey}/content",
                HttpMethod.PUT,
                HttpEntity(mapOf("content" to stencilV2()), headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val stored = withMediator { GetStencil(seeded.stencilId).query() }!!
            assertThat(stored.content.nodes).containsKey("ph-main")
        }
    }

    @Test
    fun `detail page offers Mark ready for a stencil that is not ready`() = fixture {
        lateinit var seeded: Seeded

        given { seeded = seedStencil("Detail Not Ready") }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${seeded.tenantId.key}/stencils/${seeded.catalogKey}/${seeded.stencilKey}",
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("data-testid=\"stencil-not-ready\"")
            assertThat(response.body).contains("data-testid=\"stencil-mark-ready\"")
            assertThat(response.body).contains("cannot be released until it is marked ready")
            // No versions, no publishing.
            assertThat(response.body).doesNotContain("Versions")
            assertThat(response.body).doesNotContain("/publish")
        }
    }

    @Test
    fun `HTMX POST ready marks the stencil ready and withdraws the action`() = fixture {
        lateinit var seeded: Seeded

        given { seeded = seedStencil("Mark Ready", content = stencilV1()) }

        whenever {
            postHtmx("/tenants/${seeded.tenantId.key}/stencils/${seeded.catalogKey}/${seeded.stencilKey}/ready")
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("data-testid=\"stencil-ready\"")
            assertThat(response.body).doesNotContain("data-testid=\"stencil-mark-ready\"")
        }
    }

    @Test
    fun `JSON POST ready answers the editor with JSON`() = fixture {
        lateinit var seeded: Seeded

        given { seeded = seedStencil("Mark Ready JSON", content = stencilV1()) }

        whenever {
            postJson("/tenants/${seeded.tenantId.key}/stencils/${seeded.catalogKey}/${seeded.stencilKey}/ready", "{}")
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("\"ready\":true")
        }
    }

    @Test
    fun `HTMX POST settings toggles whether the stencil reads the template's data`() = fixture {
        lateinit var seeded: Seeded

        given { seeded = seedStencil("Reads Template Data") }

        whenever {
            restTemplate.postForEntity(
                "/tenants/${seeded.tenantId.key}/stencils/${seeded.catalogKey}/${seeded.stencilKey}/settings",
                HttpEntity<MultiValueMap<String, String>>(LinkedMultiValueMap(mapOf("readsTemplateData" to listOf("true"))), htmxFormHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(withMediator { GetStencil(seeded.stencilId).query() }!!.readsTemplateData).isTrue()
            assertThat(response.body).contains("data-testid=\"stencil-reads-template-data\"")
        }
    }

    @Test
    fun `HTMX GET usage lists each variant and whether it still fits`() = fixture {
        lateinit var seeded: SeededUsage

        given {
            seeded = seedStencilUsedByTemplate("Usage Fits")
            // The stencil drops the placeholder the template fills, so the template no longer fits.
            withMediator { UpdateStencil(id = seeded.stencilId, content = stencilV2()).execute() }
        }

        whenever {
            val headers = HttpHeaders().apply { set("HX-Request", "true") }
            restTemplate.exchange(
                "/tenants/${seeded.tenantId.key}/stencils/${seeded.stencilCatalogKey}/${seeded.stencilKey}/usage",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("data-testid=\"stencil-usage-row\"")
            assertThat(response.body).contains(seeded.variantKey)
            assertThat(response.body).contains("Does not fit")
            assertThat(response.body).doesNotContain("upgrade")
        }
    }

    /**
     * Regression for #466: the JSON branch of `StencilHandler.search` parsed the
     * `?catalog=` query parameter and then discarded it before calling
     * `ListStencilSummaries`, so the inline stencil picker in the template editor
     * could not be scoped to a single catalog. The HTMX branch already honoured
     * the same parameter; this asserts the JSON path now does too.
     */
    @Test
    fun `JSON search honours the catalog query param`() = fixture {
        data class Seed(val tenantId: TenantId, val catalogA: String, val catalogB: String, val slugA: String, val slugB: String)
        lateinit var seed: Seed

        given {
            seed = withMediator {
                val tenant = createTenant("Stencil JSON Catalog Filter")
                val tenantId = TenantId(tenant.id)
                val catalogA = CatalogKey.of("json-cat-a")
                val catalogB = CatalogKey.of("json-cat-b")
                CreateCatalog(tenantKey = tenant.id, id = catalogA, name = "JSON A").execute()
                CreateCatalog(tenantKey = tenant.id, id = catalogB, name = "JSON B").execute()
                val stencilA = StencilId(StencilKey.of("json-a"), CatalogId(catalogA, tenantId))
                val stencilB = StencilId(StencilKey.of("json-b"), CatalogId(catalogB, tenantId))
                CreateStencil(id = stencilA, name = "Stencil in A").execute()
                CreateStencil(id = stencilB, name = "Stencil in B").execute()
                Seed(tenantId, catalogA.value, catalogB.value, stencilA.key.value, stencilB.key.value)
            }
        }

        whenever {
            val headers = HttpHeaders().apply { accept = listOf(MediaType.APPLICATION_JSON) }
            restTemplate.exchange(
                "/tenants/${seed.tenantId.key}/stencils/search?catalog=${seed.catalogA}",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains(seed.slugA)
            assertThat(response.body).doesNotContain(seed.slugB)
        }
    }

    // ── Create dialog (URL-addressable server-sent dialog) ──────────────────
    //
    // The New Stencil create form is a server-sent, URL-addressable dialog
    // (docs/dialog-forms.md), mirroring the theme conversion. The twist unique to
    // stencils: the POST endpoint ALSO serves the editor's JSON API, so the UI form
    // is HTMX-only (no no-JS form-POST fallback). Branches: HTMX GET → dialog
    // fragment; HTMX POST invalid → retarget form + 422; HTMX POST valid →
    // HX-Location; plain list route → mount stays empty; and the JSON API (non-HTMX
    // POST) still returns 201.

    @Test
    fun `HTMX GET new returns the dialog fragment with the create form and authored catalogs`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Stencil New Dialog")
            // An extra authored catalog alongside the auto-created "Default" one,
            // so the <select> demonstrably lists authored catalogs.
            withMediator {
                CreateCatalog(tenantKey = testTenant.id, id = CatalogKey.of("marketing"), name = "Marketing").execute()
            }
        }

        whenever {
            restTemplate.exchange(
                "/tenants/${testTenant.id}/stencils/new",
                HttpMethod.GET,
                HttpEntity<Void>(htmxHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // The dialog-shell chrome + the caller-owned form.
            assertThat(response.body).contains("""id="create-stencil-dialog"""")
            assertThat(response.body).contains("ep-dialog")
            assertThat(response.body).contains("""id="create-stencil-form"""")
            // The catalog <select> populated from authored catalogs.
            assertThat(response.body).contains("""name="catalog"""")
            assertThat(response.body).contains("Marketing")
            assertThat(response.body).contains("Default")
            assertThat(response.body).contains("""name="name"""")
            assertThat(response.body).contains("""name="slug"""")
            assertThat(response.body).contains("""name="description"""")
            assertThat(response.body).contains("""name="tags"""")
            // It is a fragment, not the whole page (no app shell).
            assertThat(response.body).doesNotContain("<html")
        }
    }

    @Test
    fun `HTMX POST invalid retargets the form with 422, inline errors, and preserved values incl tags`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Stencil Create Invalid") }

        whenever {
            val form: MultiValueMap<String, String> = LinkedMultiValueMap()
            form.add("catalog", "default")
            form.add("name", "Valid Name")
            form.add("slug", "INVALID SLUG") // uppercase + space → fails the pattern
            form.add("tags", "header, corporate")
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/stencils",
                HttpEntity(form, htmxFormHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
            // Retargets the FORM (not the dialog, not the list) so the open modal
            // dialog is never removed from the top layer.
            assertThat(response.headers.getFirst("HX-Retarget")).isEqualTo("#create-stencil-form")
            assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("outerHTML")
            // No closeDialog / redirect — the dialog stays open showing the error.
            assertThat(response.headers.getFirst("HX-Location")).isNull()
            assertThat(response.headers.getFirst("HX-Redirect")).isNull()
            // The re-rendered form: preserved values + an inline error.
            assertThat(response.body).contains("""id="create-stencil-form"""")
            assertThat(response.body).contains("form-error")
            assertThat(response.body).contains("value=\"Valid Name\"")
            assertThat(response.body).contains("value=\"INVALID SLUG\"")
            // The tags field is declared, so its value survives the error re-render.
            assertThat(response.body).contains("value=\"header, corporate\"")
            // authoredCatalogs prefill survived → the <select> is still populated.
            assertThat(response.body).contains("""name="catalog"""")
            assertThat(response.body).contains("Default")
        }
    }

    @Test
    fun `HTMX POST valid returns HX-Location to the new stencil page`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Stencil Create Valid") }

        whenever {
            val form: MultiValueMap<String, String> = LinkedMultiValueMap()
            form.add("catalog", "default")
            form.add("name", "Corporate Header")
            form.add("slug", "corporate-header")
            form.add("description", "Our house header")
            form.add("tags", "header, corporate")
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/stencils",
                HttpEntity(form, htmxFormHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            // Success soft-navigates the page to the created stencil via a boosted
            // body-swap (HX-Location); the dialog goes with the swapped-out body —
            // asserted via the header, not a body. No HX-Redirect (full reload).
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.headers.getFirst("HX-Location"))
                .isEqualTo("/tenants/${testTenant.id}/stencils/default/corporate-header")
            assertThat(response.headers.getFirst("HX-Redirect")).isNull()
        }
    }

    @Test
    fun `boosted POST create does not 500 (stale pre-conversion tab)`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Stencil Boosted Create") }

        whenever {
            // A tab left open from before this branch converted the stencil form
            // submits the old plain method=post form as an hx-boosted POST
            // (HX-Request + HX-Boosted). build() routes boosted requests to the
            // non-HTMX branch; without an onNonHtmx fallback that branch threw
            // IllegalStateException → 500 even for a valid create.
            val form: MultiValueMap<String, String> = LinkedMultiValueMap()
            form.add("catalog", "default")
            form.add("name", "Boosted Header")
            form.add("slug", "boosted-header")
            val headers = HttpHeaders().apply {
                set("HX-Request", "true")
                set("HX-Boosted", "true")
                contentType = MediaType.APPLICATION_FORM_URLENCODED
            }
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/stencils",
                HttpEntity(form, headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            // Pre-fix: 500 (IllegalStateException). The onNonHtmx { redirect(...) }
            // fallback now keeps a valid boosted create out of the 5xx range.
            assertThat(response.statusCode.value()).isLessThan(500)
        }
    }

    @Test
    fun `plain list route does not embed the create dialog`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Stencil Plain List") }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${testTenant.id}/stencils",
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // Full host page, but the mount is empty: no openDialog flag on the
            // plain list route, so the create dialog must NOT be embedded (the
            // th:if guard must wrap the th:replace). See docs/dialog-forms.md.
            assertThat(response.body).contains("""id="dialog-mount"""")
            assertThat(response.body).doesNotContain("""id="create-stencil-dialog"""")
            assertThat(response.body).doesNotContain("create-stencil-form")
        }
    }

    /**
     * Landmine guard: the POST endpoint doubles as the editor's JSON API. A
     * non-HTMX POST with a JSON body must STILL hit createJson and return 201 —
     * the dialog conversion must not disturb this path.
     */
    @Test
    fun `non-HTMX JSON POST still creates a stencil via the JSON API with 201`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Stencil JSON API") }

        whenever {
            val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/stencils",
                HttpEntity(
                    """{"id":"json-created","name":"JSON Created","catalogKey":"default"}""",
                    headers,
                ),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            // The JSON API path is untouched by the dialog conversion: 201 + JSON body.
            assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
            assertThat(response.body).contains("\"stencilId\":\"json-created\"")
            assertThat(response.body).contains("\"catalogKey\":\"default\"")
        }
    }

    private fun htmxHeaders() = HttpHeaders().apply { set("HX-Request", "true") }

    private fun htmxFormHeaders() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_FORM_URLENCODED
        set("HX-Request", "true")
    }

    private data class Seeded(
        val tenantId: TenantId,
        val stencilId: StencilId,
    ) {
        val catalogKey: String get() = stencilId.catalogKey.value
        val stencilKey: String get() = stencilId.key.value
    }

    private data class SeededUsage(
        val tenantId: TenantId,
        val stencilId: StencilId,
        val templateKey: String,
        val variantKey: String,
    ) {
        val stencilCatalogKey: String get() = stencilId.catalogKey.value
        val stencilKey: String get() = stencilId.key.value
    }

    private fun seedStencil(name: String, content: TemplateDocument? = null): Seeded = withMediator {
        val tenant: Tenant = createTenant(name)
        val tenantId = TenantId(tenant.id)
        val stencilId = StencilId(TestIdHelpers.nextStencilId(), CatalogId.default(tenantId))
        CreateStencil(id = stencilId, name = name, content = content).execute()
        Seeded(tenantId, stencilId)
    }

    /** A stencil embedded in a template's default variant, both in the default catalog. */
    private fun seedStencilUsedByTemplate(name: String): SeededUsage = withMediator {
        val tenant: Tenant = createTenant(name)
        val tenantId = TenantId(tenant.id)
        val stencilId = StencilId(TestIdHelpers.nextStencilId(), CatalogId.default(tenantId))
        CreateStencil(id = stencilId, name = name, content = stencilV1()).execute()

        val templateKey = TestIdHelpers.nextTemplateId()
        val templateId = TemplateId(templateKey, CatalogId.default(tenantId))
        CreateDocumentTemplate(id = templateId, name = "Letter $name").execute()
        val variantKey = VariantKey.INITIAL
        UpdateVariantContent(
            variantId = VariantId(variantKey, templateId),
            templateModel = templateEmbedding(stencilId.key.value),
        ).execute()

        SeededUsage(tenantId = tenantId, stencilId = stencilId, templateKey = templateKey.value, variantKey = variantKey.value)
    }

    /** Stencil v1: a placeholder named "body" with default text. */
    private fun stencilV1(): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "ph-body" to Node(
                id = "ph-body",
                type = "placeholder",
                slots = listOf("ph-body-fill"),
                props = mapOf("name" to "body", "kind" to "block"),
            ),
            "default-text" to Node(
                id = "default-text",
                type = "text",
                slots = emptyList(),
                props = mapOf("content" to richText("Default body content")),
            ),
        ),
        slots = mapOf(
            "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("ph-body")),
            "ph-body-fill" to Slot(id = "ph-body-fill", nodeId = "ph-body", name = "fill", children = listOf("default-text")),
        ),
        themeRef = ThemeRef.Inherit,
    )

    /** Stencil v2: placeholder renamed "body" → "main", so a template filling "body" no longer fits. */
    private fun stencilV2(): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "ph-main" to Node(
                id = "ph-main",
                type = "placeholder",
                slots = listOf("ph-main-fill"),
                props = mapOf("name" to "main", "kind" to "block"),
            ),
        ),
        slots = mapOf(
            "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("ph-main")),
            "ph-main-fill" to Slot(id = "ph-main-fill", nodeId = "ph-main", name = "fill", children = emptyList()),
        ),
        themeRef = ThemeRef.Inherit,
    )

    /** A template body embedding [stencilKey] v1 once, with one user-authored fill. */
    private fun templateEmbedding(stencilKey: String): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "stencil-instance" to Node(
                id = "stencil-instance",
                type = "stencil",
                slots = listOf("stencil-children"),
                props = mapOf("stencilKey" to stencilKey, "version" to 1),
            ),
            "embedded-ph" to Node(
                id = "embedded-ph",
                type = "placeholder",
                slots = listOf("embedded-ph-fill"),
                props = mapOf("name" to "body", "kind" to "block"),
            ),
            "user-fill-text" to Node(
                id = "user-fill-text",
                type = "text",
                slots = emptyList(),
                props = mapOf("content" to richText("User-authored body")),
            ),
        ),
        slots = mapOf(
            "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("stencil-instance")),
            "stencil-children" to Slot(id = "stencil-children", nodeId = "stencil-instance", name = "children", children = listOf("embedded-ph")),
            "embedded-ph-fill" to Slot(id = "embedded-ph-fill", nodeId = "embedded-ph", name = "fill", children = listOf("user-fill-text")),
        ),
        themeRef = ThemeRef.Inherit,
    )

    private fun postHtmx(url: String): org.springframework.http.ResponseEntity<String> {
        val headers = HttpHeaders().apply { set("HX-Request", "true") }
        return restTemplate.postForEntity(url, HttpEntity<Void>(headers), String::class.java)
    }

    /** Mirrors the editor's fetch: a plain JSON POST (no HX-Request). */
    private fun postJson(url: String, body: String): org.springframework.http.ResponseEntity<String> {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        return restTemplate.postForEntity(url, HttpEntity(body, headers), String::class.java)
    }

    private fun richText(text: String): Map<String, Any?> = mapOf(
        "type" to "doc",
        "content" to listOf(
            mapOf(
                "type" to "paragraph",
                "content" to listOf(mapOf("type" to "text", "text" to text)),
            ),
        ),
    )
}
