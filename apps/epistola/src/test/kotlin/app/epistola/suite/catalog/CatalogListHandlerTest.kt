package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
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
    fun `creating a catalog over HTMX redirects to the new catalog's browse page`() = fixture {
        lateinit var t: Tenant
        given { t = tenant("Catalog List Create") }

        whenever {
            val payload = LinkedMultiValueMap<String, String>()
            payload.add("slug", "list-region-cat")
            payload.add("name", "List Region Cat")
            restTemplate.postForEntity(
                "/tenants/${t.id}/catalogs/create",
                HttpEntity(payload, htmxForm()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            // Create now follows the shared dialog contract: HX-Redirect to the new
            // catalog (by slug), not an in-place OOB list swap.
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.headers.getFirst("HX-Redirect"))
                .isEqualTo("/tenants/${t.id}/catalogs/list-region-cat/browse")
        }
    }

    @Test
    fun `GET new over HTMX pushes the create URL`() = fixture {
        lateinit var t: Tenant
        given { t = tenant("Catalog New Push URL") }

        whenever {
            val headers = HttpHeaders().apply {
                add("HX-Request", "true")
                add("HX-Current-URL", "/tenants/${t.id}/catalogs")
            }
            restTemplate.exchange(
                "/tenants/${t.id}/catalogs/new",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("create-catalog-dialog")
            assertThat(response.headers.getFirst("HX-Push-Url"))
                .isEqualTo("/tenants/${t.id}/catalogs?create")
        }
    }

    @Test
    fun `GET list with create renders the dialog markup for deep linking`() = fixture {
        lateinit var t: Tenant
        given { t = tenant("Catalog Deeplink") }

        whenever {
            restTemplate.getForEntity("/tenants/${t.id}/catalogs?create", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("create-catalog-dialog")
            assertThat(response.body).contains("data-create-dialog")
        }
    }

    @Test
    fun `POST create with a blank name re-renders the form with an error, dialog stays open`() = fixture {
        lateinit var t: Tenant
        given { t = tenant("Catalog Create Error") }

        whenever {
            val payload = LinkedMultiValueMap<String, String>()
            payload.add("slug", "valid-slug")
            payload.add("name", "")
            restTemplate.postForEntity(
                "/tenants/${t.id}/catalogs/create",
                HttpEntity(payload, htmxForm()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            // Validation error self-swaps the form (no HX-Redirect), keeping the
            // dialog open with the field error.
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.headers.getFirst("HX-Redirect")).isNull()
            assertThat(response.body).contains("create-catalog-form")
            assertThat(response.body).contains("form-error")
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
