package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.CreateStencilVersion
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.TestIdHelpers
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
 * Regression cover for the stencil detail HTMX fragment flow.
 *
 * The `versions` and `usage` fragments were rendered via [StencilHandler.versionListFragment]
 * and [StencilHandler.usageDetails] with a nullable `GetStencil(...).query()` value passed
 * straight into the model DSL. The DSL's `infix fun String.to(value: Any)` could not accept
 * nullable values, so Kotlin silently resolved to `kotlin.to` (the Pair extension), the
 * pair was discarded, and `stencil` never reached the model — yielding `EL1007E` on
 * `stencil.catalogType.name()` in `stencils/detail.html`. The fragment tests below
 * would have returned HTTP 500 before that fix.
 *
 * The upgrade tests cover a second, pre-existing defect (shipped in #305): the
 * `applyUpgrade()` script in `stencils/detail.html` never sent `catalogKey`, so
 * `StencilHandler.upgradeInTemplate` rejected every request with HTTP 400
 * `{"error":"catalogKey is required"}` — the whole "upgrade stencil in template"
 * feature was non-functional and had no test. These assert the endpoint contract
 * the fixed client now relies on, plus that the usage fragment emits the
 * `data-catalog-key` attribute the client reads.
 */
class StencilHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `HTMX POST createVersion renders versions fragment with stencil model attribute`() = fixture {
        lateinit var seeded: Seeded

        given {
            seeded = seedStencilWithoutDraft("Versions Fragment Create")
        }

        whenever {
            postHtmx("/tenants/${seeded.tenantId.key}/stencils/${seeded.catalogKey}/${seeded.stencilKey}/versions")
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // Versions table is the proof the fragment rendered to completion.
            // If `stencil` were null, Thymeleaf would have thrown EL1007E before reaching the table.
            assertThat(response.body).contains("ep-table")
            assertThat(response.body).contains("v1")
        }
    }

    @Test
    fun `HTMX POST publishVersion renders versions fragment with stencil model attribute`() = fixture {
        lateinit var seeded: Seeded

        given {
            seeded = seedStencilWithDraft("Versions Fragment Publish")
        }

        whenever {
            postHtmx(
                "/tenants/${seeded.tenantId.key}/stencils/${seeded.catalogKey}/${seeded.stencilKey}/versions/1/publish",
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // After publish, the row badge reads "published" — this only renders when
            // both `versions` and `stencil` are populated (the Publish/Archive button
            // visibility hinges on `stencil.catalogType.name()`).
            assertThat(response.body).contains("published")
        }
    }

    @Test
    fun `HTMX GET usage details renders usage fragment with stencil and catalogId`() = fixture {
        lateinit var seeded: Seeded

        given {
            seeded = seedStencilWithPublished("Usage Fragment")
        }

        whenever {
            val headers = HttpHeaders().apply { set("HX-Request", "true") }
            restTemplate.exchange(
                "/tenants/${seeded.tenantId.key}/stencils/${seeded.catalogKey}/${seeded.stencilKey}/usage",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // The upgrade picker is the visible proof; it requires `versions` AND the
            // surrounding markup uses `stencil` for the `AUTHORED` gate.
            assertThat(response.body).contains("upgrade-version-select")
        }
    }

    @Test
    fun `POST upgrade with the template catalogKey upgrades the stencil in the template draft`() = fixture {
        lateinit var seeded: SeededUsage

        given {
            seeded = seedStencilUsedByTemplateDraft("Upgrade With Catalog Key")
        }

        whenever {
            postJson(
                "/tenants/${seeded.tenantId.key}/stencils/${seeded.stencilCatalogKey}/${seeded.stencilKey}/upgrade",
                """{"templateId":"${seeded.templateKey}","variantId":"${seeded.variantKey}","catalogKey":"${seeded.templateCatalogKey}","newVersion":2}""",
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // `upgraded` is the count field StencilHandler.upgradeInTemplate returns
            // on success; its presence proves the request got past the catalogKey gate.
            assertThat(response.body).contains("\"upgraded\"")
            assertThat(response.body).doesNotContain("catalogKey is required")
        }
    }

    @Test
    fun `POST upgrade against a published template creates a draft and upgrades it`() = fixture {
        lateinit var seeded: SeededUsage

        given {
            seeded = seedStencilUsedByPublishedTemplate("Upgrade Published Template")
        }

        whenever {
            postJson(
                "/tenants/${seeded.tenantId.key}/stencils/${seeded.stencilCatalogKey}/${seeded.stencilKey}/upgrade",
                """{"templateId":"${seeded.templateKey}","variantId":"${seeded.variantKey}","catalogKey":"${seeded.templateCatalogKey}","newVersion":2}""",
            )
        }

        then {
            // Previously this returned 400 "No draft version found" — a published
            // template with no open draft could not be bulk-upgraded. Now the command
            // creates a draft seeded from the published version and upgrades there.
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("\"upgraded\"")
            assertThat(response.body).doesNotContain("No draft version found")
        }
    }

    @Test
    fun `POST upgrade without catalogKey is rejected with the documented error`() = fixture {
        lateinit var seeded: SeededUsage

        given {
            seeded = seedStencilUsedByTemplateDraft("Upgrade Missing Catalog Key")
        }

        whenever {
            postJson(
                "/tenants/${seeded.tenantId.key}/stencils/${seeded.stencilCatalogKey}/${seeded.stencilKey}/upgrade",
                """{"templateId":"${seeded.templateKey}","variantId":"${seeded.variantKey}","newVersion":2}""",
            )
        }

        then {
            // Locks the contract: the omission the old client shipped must stay a
            // hard 400, not silently resolve to the stencil's own catalog.
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(response.body).contains("catalogKey is required")
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

    @Test
    fun `HTMX GET usage details renders selectable rows carrying data-catalog-key`() = fixture {
        lateinit var seeded: SeededUsage

        given {
            seeded = seedStencilUsedByTemplateDraft("Usage Data Catalog Key")
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
            // The selectable checkbox must carry the template's own catalog so
            // applyUpgrade() can put it in the request body. Without this attribute
            // the upgrade POST 400s with "catalogKey is required".
            assertThat(response.body).contains("data-catalog-key=\"${seeded.templateCatalogKey}\"")
        }
    }

    @Test
    fun `HTMX GET usage details with filter=upgradable shows only upgradable rows`() = fixture {
        lateinit var seeded: SeededUsage

        given {
            seeded = seedStencilUsedByPublishedAndDraftTemplate("Usage Filter Upgradable")
        }

        whenever {
            val headers = HttpHeaders().apply { set("HX-Request", "true") }
            restTemplate.exchange(
                "/tenants/${seeded.tenantId.key}/stencils/${seeded.stencilCatalogKey}/${seeded.stencilKey}/usage?filter=upgradable",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java,
            )
        }

        then {
            // The variant has a published (non-upgradable, "has draft") row and a draft
            // (upgradable) row. filter=upgradable shows just the draft.
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("1 of 2 upgradable")
            assertThat(response.body).contains("usage-select") // the draft row's checkbox
            // The filtered-out published row's block reason must not appear.
            assertThat(response.body).doesNotContain("already has an open draft")
        }
    }

    @Test
    fun `HTMX GET usage details with filter=not-upgradable shows only blocked rows`() = fixture {
        lateinit var seeded: SeededUsage

        given {
            seeded = seedStencilUsedByPublishedAndDraftTemplate("Usage Filter Blocked")
        }

        whenever {
            val headers = HttpHeaders().apply { set("HX-Request", "true") }
            restTemplate.exchange(
                "/tenants/${seeded.tenantId.key}/stencils/${seeded.stencilCatalogKey}/${seeded.stencilKey}/usage?filter=not-upgradable",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java,
            )
        }

        then {
            // filter=not-upgradable shows just the blocked published row (its reason),
            // and no selectable checkbox.
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("already has an open draft")
            assertThat(response.body).doesNotContain("usage-select")
        }
    }

    @Test
    fun `stencil detail page shows a per-version Uses column`() = fixture {
        lateinit var seeded: SeededUsage

        given {
            seeded = seedStencilUsedByTemplateDraft("Version Usage Counts")
        }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${seeded.tenantId.key}/stencils/${seeded.stencilCatalogKey}/${seeded.stencilKey}",
                String::class.java,
            )
        }

        then {
            // The versions table renders a Uses column (count of embedded instances
            // across draft/published templates). v1 is embedded once by the seed.
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Uses")
        }
    }

    // ── Create dialog (URL-addressable server-sent dialog) ──────────────────
    //
    // The New Stencil create form is a server-sent, URL-addressable dialog
    // (docs/dialog-forms.md), mirroring the theme conversion. The twist unique to
    // stencils: the POST endpoint ALSO serves the editor's JSON API, so the UI form
    // is HTMX-only (no no-JS form-POST fallback). Branches: HTMX GET → dialog
    // fragment; HTMX POST invalid → retarget form + 422; HTMX POST valid →
    // HX-Redirect; plain list route → mount stays empty; and the JSON API (non-HTMX
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
    fun `HTMX POST valid returns HX-Redirect to the new stencil page`() = fixture {
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
            // Success navigates the whole page to the created stencil (the dialog
            // goes with it) — asserted via the header, not a body.
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.headers.getFirst("HX-Redirect"))
                .isEqualTo("/tenants/${testTenant.id}/stencils/default/corporate-header")
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
        val catalogKey: String,
        val stencilKey: String,
    )

    private data class SeededUsage(
        val tenantId: TenantId,
        val stencilCatalogKey: String,
        val stencilKey: String,
        val templateKey: String,
        val variantKey: String,
        val templateCatalogKey: String,
    )

    private fun seedStencilWithoutDraft(name: String): Seeded = withMediator {
        val tenant: Tenant = createTenant(name)
        val tenantId = TenantId(tenant.id)
        val stencilId = StencilId(TestIdHelpers.nextStencilId(), CatalogId.default(tenantId))
        CreateStencil(id = stencilId, name = name).execute()
        Seeded(tenantId, stencilId.catalogKey.value, stencilId.key.value)
    }

    private fun seedStencilWithDraft(name: String): Seeded = withMediator {
        val tenant: Tenant = createTenant(name)
        val tenantId = TenantId(tenant.id)
        val stencilId = StencilId(TestIdHelpers.nextStencilId(), CatalogId.default(tenantId))
        CreateStencil(id = stencilId, name = name).execute()
        CreateStencilVersion(stencilId = stencilId).execute()
        Seeded(tenantId, stencilId.catalogKey.value, stencilId.key.value)
    }

    private fun seedStencilWithPublished(name: String): Seeded = withMediator {
        val tenant: Tenant = createTenant(name)
        val tenantId = TenantId(tenant.id)
        val stencilId = StencilId(TestIdHelpers.nextStencilId(), CatalogId.default(tenantId))
        CreateStencil(id = stencilId, name = name).execute()
        CreateStencilVersion(stencilId = stencilId).execute()
        PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(1), stencilId)).execute()
        Seeded(tenantId, stencilId.catalogKey.value, stencilId.key.value)
    }

    /**
     * Stencil v1 published, embedded into a template's default draft variant, with a
     * v2 published and ready to upgrade to. Mirrors the seed recipe proven by
     * `StencilPlaceholderIntegrationTest`. Stencil and template share the default
     * catalog, so `stencilCatalogKey == templateCatalogKey` here — the test still
     * exercises the wire path that was broken (client must send the catalog at all).
     */
    private fun seedStencilUsedByTemplateDraft(name: String): SeededUsage = withMediator {
        val tenant: Tenant = createTenant(name)
        val tenantId = TenantId(tenant.id)
        val stencilId = StencilId(TestIdHelpers.nextStencilId(), CatalogId.default(tenantId))
        CreateStencil(id = stencilId, name = name, content = stencilV1()).execute()
        PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(1), stencilId)).execute()

        val templateKey = TestIdHelpers.nextTemplateId()
        val templateId = TemplateId(templateKey, CatalogId.default(tenantId))
        CreateDocumentTemplate(id = templateId, name = "Letter $name").execute()
        val variantKey = VariantKey.INITIAL
        UpdateDraft(
            variantId = VariantId(variantKey, templateId),
            templateModel = templateEmbedding(stencilId.key.value),
        ).execute()

        CreateStencilVersion(stencilId = stencilId, content = stencilV2()).execute()
        PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(2), stencilId)).execute()

        SeededUsage(
            tenantId = tenantId,
            // Same default catalog for both; captured separately to keep the wire
            // contract explicit (the bug was the client omitting the template catalog).
            stencilCatalogKey = stencilId.catalogKey.value,
            stencilKey = stencilId.key.value,
            templateKey = templateKey.value,
            variantKey = variantKey.value,
            templateCatalogKey = stencilId.catalogKey.value,
        )
    }

    /**
     * Same as [seedStencilUsedByTemplateDraft], but the template version is
     * published — leaving NO open draft. Exercises the published-template upgrade
     * path (issue #598): the command must create a draft to upgrade into.
     */
    private fun seedStencilUsedByPublishedTemplate(name: String): SeededUsage = withMediator {
        val tenant: Tenant = createTenant(name)
        val tenantId = TenantId(tenant.id)
        val stencilId = StencilId(TestIdHelpers.nextStencilId(), CatalogId.default(tenantId))
        CreateStencil(id = stencilId, name = name, content = stencilV1()).execute()
        PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(1), stencilId)).execute()

        val templateKey = TestIdHelpers.nextTemplateId()
        val templateId = TemplateId(templateKey, CatalogId.default(tenantId))
        CreateDocumentTemplate(id = templateId, name = "Letter $name").execute()
        val variantKey = VariantKey.INITIAL
        val variantId = VariantId(variantKey, templateId)
        UpdateDraft(
            variantId = variantId,
            templateModel = templateEmbedding(stencilId.key.value),
        ).execute()
        // Publish the template version — now there is no draft to upgrade.
        PublishVersion(versionId = VersionId(VersionKey.of(1), variantId)).execute()

        CreateStencilVersion(stencilId = stencilId, content = stencilV2()).execute()
        PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(2), stencilId)).execute()

        SeededUsage(
            tenantId = tenantId,
            stencilCatalogKey = stencilId.catalogKey.value,
            stencilKey = stencilId.key.value,
            templateKey = templateKey.value,
            variantKey = variantKey.value,
            templateCatalogKey = stencilId.catalogKey.value,
        )
    }

    /**
     * A variant that has BOTH a published version and an open draft embedding the
     * stencil — so its usage yields one non-upgradable row (the published, blocked
     * by HAS_DRAFT) and one upgradable row (the draft). Used to exercise the
     * "show only upgradable" filter.
     */
    private fun seedStencilUsedByPublishedAndDraftTemplate(name: String): SeededUsage = withMediator {
        val tenant: Tenant = createTenant(name)
        val tenantId = TenantId(tenant.id)
        val stencilId = StencilId(TestIdHelpers.nextStencilId(), CatalogId.default(tenantId))
        CreateStencil(id = stencilId, name = name, content = stencilV1()).execute()
        PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(1), stencilId)).execute()

        val templateKey = TestIdHelpers.nextTemplateId()
        val templateId = TemplateId(templateKey, CatalogId.default(tenantId))
        CreateDocumentTemplate(id = templateId, name = "Letter $name").execute()
        val variantKey = VariantKey.INITIAL
        val variantId = VariantId(variantKey, templateId)
        UpdateDraft(variantId = variantId, templateModel = templateEmbedding(stencilId.key.value)).execute()
        PublishVersion(versionId = VersionId(VersionKey.of(1), variantId)).execute()
        // Re-open a draft → the variant now has a published v1 AND a draft v2.
        UpdateDraft(variantId = variantId, templateModel = templateEmbedding(stencilId.key.value)).execute()

        CreateStencilVersion(stencilId = stencilId, content = stencilV2()).execute()
        PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(2), stencilId)).execute()

        SeededUsage(
            tenantId = tenantId,
            stencilCatalogKey = stencilId.catalogKey.value,
            stencilKey = stencilId.key.value,
            templateKey = templateKey.value,
            variantKey = variantKey.value,
            templateCatalogKey = stencilId.catalogKey.value,
        )
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
                props = mapOf("content" to "Default body content"),
            ),
        ),
        slots = mapOf(
            "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("ph-body")),
            "ph-body-fill" to Slot(id = "ph-body-fill", nodeId = "ph-body", name = "fill", children = listOf("default-text")),
        ),
        themeRef = ThemeRef.Inherit,
    )

    /** Stencil v2: placeholder renamed "body" → "main" (forces a dropped fill on upgrade). */
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
                props = mapOf("stencilId" to stencilKey, "version" to 1),
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
                props = mapOf("content" to "User-authored body"),
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

    /** Mirrors the browser's `applyUpgrade()` fetch: a plain JSON POST (no HX-Request). */
    private fun postJson(url: String, body: String): org.springframework.http.ResponseEntity<String> {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        return restTemplate.postForEntity(url, HttpEntity(body, headers), String::class.java)
    }
}
