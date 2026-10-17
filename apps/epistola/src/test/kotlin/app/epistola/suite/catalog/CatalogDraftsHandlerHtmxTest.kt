// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.queries.ListAttributeDefinitions
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionStatus
import app.epistola.suite.catalog.versions.CreateCatalogVersion
import app.epistola.suite.catalog.versions.DeleteCatalogVersion
import app.epistola.suite.catalog.versions.ListCatalogVersions
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.variants.GetVariantContent
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.SetTenantDefaultTheme
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.themes.queries.GetTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.LinkedMultiValueMap
import tools.jackson.databind.ObjectMapper

/**
 * Several drafts of a catalog in the UI (contract ADR 0002): the draft travels in the catalog's path
 * segment (`letters@1.0.1`), the catalog page and the content pages work on the draft it names, and
 * the catalog page opens, discards, renumbers and releases the chosen draft.
 */
class CatalogDraftsHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val letters = CatalogKey.of("letters")

    private fun catalogId(tenant: Tenant) = CatalogId(letters, TenantId(tenant.id))

    private fun brand(tenant: Tenant) = ThemeId(ThemeKey.of("brand"), catalogId(tenant))

    private fun invoice(tenant: Tenant) = TemplateId(TemplateKey.of("invoice"), catalogId(tenant))

    private fun at(tenant: Tenant, version: String) = CatalogVersionRef(tenant.id, letters, version)

    /** `letters` (brand theme, invoice template) released as 1.0.0: one draft, 1.0.1. */
    private fun released(tenant: Tenant) = withMediator {
        CreateCatalog(tenantKey = tenant.id, id = letters, name = "Letters").execute()
        CreateTheme(brand(tenant), "Brand").execute()
        CreateDocumentTemplate(invoice(tenant), "Invoice").execute().withRequiredDataExample()
        MarkCatalogReady(tenant.id, letters).execute()
        ReleaseCatalogVersion(tenant.id, letters, "1.0.0").execute()
    }

    /** [released], then a second draft 2.0.0, and the invoice named differently in each. */
    private fun twoDrafts(tenant: Tenant) {
        released(tenant)
        withMediator {
            CreateCatalogVersion(tenant.id, letters, "2.0.0").execute()
            UpdateDocumentTemplate(invoice(tenant), name = "Invoice, maintenance", catalogVersion = at(tenant, "1.0.1")).execute()
            UpdateDocumentTemplate(invoice(tenant), name = "Invoice two", catalogVersion = at(tenant, "2.0.0")).execute()
        }
    }

    private fun drafts(tenant: Tenant) = withMediator { ListCatalogVersions(tenant.id, letters, CatalogVersionStatus.DRAFT).query() }.map { it.version }

    @Test
    fun `a catalog with one draft shows its number and no picker`() = fixture {
        lateinit var tenant: Tenant
        given {
            tenant = tenant("Drafts One")
            released(tenant)
        }
        whenever { get("/tenants/${tenant.id}/catalogs/letters/browse") }
        then {
            val body = result<ResponseEntity<String>>().body!!
            assertThat(body).contains("data-testid=\"catalog-draft-version\"").contains("v1.0.1")
            assertThat(body).doesNotContain("data-testid=\"catalog-draft-picker\"")
            assertThat(body).doesNotContain("data-testid=\"catalog-draft-bar\"")
            assertThat(body).contains("data-testid=\"catalog-draft-new\"")
        }
    }

    @Test
    fun `with two drafts the catalog page offers both and works on the one its segment names`() = fixture {
        lateinit var tenant: Tenant
        given {
            tenant = tenant("Drafts Picker")
            twoDrafts(tenant)
        }
        whenever { get("/tenants/${tenant.id}/catalogs/letters@1.0.1/browse") }
        then {
            val body = result<ResponseEntity<String>>().body!!
            assertThat(body).contains("data-testid=\"catalog-draft-picker\"")
            // The current draft is linked plainly, the other by its number.
            assertThat(body).contains("href=\"/tenants/${tenant.id}/catalogs/letters/browse\"")
            assertThat(body).contains("data-testid=\"catalog-draft-chosen\">v1.0.1<")
            assertThat(body).contains("data-testid=\"catalog-draft-version\">v1.0.1<")
            // Its resources link into the draft, and its actions act on it.
            assertThat(body).contains("/tenants/${tenant.id}/templates/letters@1.0.1/invoice")
            assertThat(body).contains("/tenants/${tenant.id}/catalogs/letters@1.0.1/renumber")
            assertThat(body).contains("/tenants/${tenant.id}/catalogs/letters@1.0.1/drafts/discard")
        }
    }

    @Test
    fun `a version the catalog does not have as a draft is not found`() = fixture {
        lateinit var tenant: Tenant
        given {
            tenant = tenant("Drafts Unknown")
            released(tenant)
        }
        whenever { get("/tenants/${tenant.id}/templates/letters@9.9.9/invoice") }
        then {
            assertThat(result<ResponseEntity<String>>().statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        }
    }

    @Test
    fun `the template page in a draft shows that draft's template, with the draft bar, and links stay in it`() = fixture {
        lateinit var tenant: Tenant
        given {
            tenant = tenant("Drafts Template")
            twoDrafts(tenant)
        }
        whenever { get("/tenants/${tenant.id}/templates/letters@1.0.1/invoice") }
        then {
            val body = result<ResponseEntity<String>>().body!!
            assertThat(body).contains("Invoice, maintenance").doesNotContain("Invoice two")
            assertThat(body).contains("data-testid=\"catalog-draft-bar\"").contains("data-testid=\"catalog-draft-chosen\">v1.0.1<")
            assertThat(body).contains("/tenants/${tenant.id}/templates/letters@1.0.1/invoice/settings")
            assertThat(body).contains("/tenants/${tenant.id}/templates/letters@1.0.1/invoice/variants/initial/editor")
        }
    }

    @Test
    fun `the plain template page is the current draft's, and with one draft it has no draft bar`() = fixture {
        lateinit var tenant: Tenant
        lateinit var several: String
        given {
            tenant = tenant("Drafts Template Plain")
            released(tenant)
        }
        whenever {
            val one = get("/tenants/${tenant.id}/templates/letters/invoice").body!!
            withMediator { CreateCatalogVersion(tenant.id, letters, "2.0.0").execute() }
            withMediator { UpdateDocumentTemplate(invoice(tenant), name = "Invoice two", catalogVersion = at(tenant, "2.0.0")).execute() }
            several = get("/tenants/${tenant.id}/templates/letters/invoice").body!!
            one
        }
        then {
            assertThat(result<String>()).doesNotContain("data-testid=\"catalog-draft-bar\"")
            assertThat(several).contains("Invoice two").contains("data-testid=\"catalog-draft-bar\"")
        }
    }

    @Test
    fun `the editor saves the variant into the draft its route names`() = fixture {
        lateinit var tenant: Tenant
        given {
            tenant = tenant("Drafts Save")
            twoDrafts(tenant)
        }
        whenever {
            val variant = VariantId(VariantKey.INITIAL, invoice(tenant))
            val model = withMediator { GetVariantContent(variant, catalogVersion = at(tenant, "1.0.1")).query() }!!
            val json = objectMapper.writeValueAsString(mapOf("templateModel" to model.copy(documentStylesOverride = mapOf("color" to "#123456"))))
            send(HttpMethod.PUT, "/tenants/${tenant.id}/templates/letters@1.0.1/invoice/variants/initial/content", json)
        }
        then {
            assertThat(result<ResponseEntity<String>>().statusCode).isEqualTo(HttpStatus.OK)
            val variant = VariantId(VariantKey.INITIAL, invoice(tenant))
            assertThat(withMediator { GetVariantContent(variant, catalogVersion = at(tenant, "1.0.1")).query() }!!.documentStylesOverride).containsEntry("color", "#123456")
            assertThat(withMediator { GetVariantContent(variant, catalogVersion = at(tenant, "2.0.0")).query() }!!.documentStylesOverride.orEmpty()).doesNotContainKey("color")
        }
    }

    @Test
    fun `a theme saved from its page in a draft changes only that draft`() = fixture {
        lateinit var tenant: Tenant
        given {
            tenant = tenant("Drafts Theme")
            twoDrafts(tenant)
        }
        whenever {
            val page = get("/tenants/${tenant.id}/themes/letters@1.0.1/brand").body!!
            assertThat(page).contains("\"catalogSegment\": \"letters@1.0.1\"")
            send(HttpMethod.PATCH, "/tenants/${tenant.id}/themes/letters@1.0.1/brand", """{"name":"Brand, maintenance"}""")
        }
        then {
            assertThat(result<ResponseEntity<String>>().statusCode).isEqualTo(HttpStatus.OK)
            assertThat(withMediator { GetTheme(brand(tenant), catalogVersion = at(tenant, "1.0.1")).query() }!!.name).isEqualTo("Brand, maintenance")
            assertThat(withMediator { GetTheme(brand(tenant), catalogVersion = at(tenant, "2.0.0")).query() }!!.name).isEqualTo("Brand")
        }
    }

    @Test
    fun `a stencil page in a draft shows the draft's stencil`() = fixture {
        lateinit var tenant: Tenant
        given {
            tenant = tenant("Drafts Stencil")
            twoDrafts(tenant)
            withMediator { CreateStencil(StencilId(StencilKey.of("footer"), catalogId(tenant)), "Footer", catalogVersion = at(tenant, "1.0.1")).execute() }
        }
        whenever {
            val inDraft = get("/tenants/${tenant.id}/stencils/letters@1.0.1/footer")
            val current = get("/tenants/${tenant.id}/stencils/letters/footer")
            inDraft to current
        }
        then {
            val (inDraft, current) = result<Pair<ResponseEntity<String>, ResponseEntity<String>>>()
            assertThat(inDraft.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(inDraft.body).contains("/tenants/${tenant.id}/stencils/letters@1.0.1/footer/ready")
            assertThat(current.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        }
    }

    @Test
    fun `the attributes list filtered to a draft lists its attributes, and creating there lands in it`() = fixture {
        lateinit var tenant: Tenant
        given {
            tenant = tenant("Drafts Attributes")
            twoDrafts(tenant)
            withMediator { CreateAttributeDefinition(AttributeId(AttributeKey.of("tone"), catalogId(tenant)), "Tone", catalogVersion = at(tenant, "1.0.1")).execute() }
        }
        whenever {
            val list = get("/tenants/${tenant.id}/attributes?catalog=letters@1.0.1").body!!
            assertThat(list).contains("Tone")
            assertThat(get("/tenants/${tenant.id}/attributes?catalog=letters").body!!).doesNotContain(">Tone<")
            val dialog = getHtmx("/tenants/${tenant.id}/attributes/new?catalog=letters@1.0.1").body!!
            assertThat(dialog).contains("value=\"letters@1.0.1\"")
            postForm("/tenants/${tenant.id}/attributes", mapOf("catalog" to "letters@1.0.1", "slug" to "mood", "displayName" to "Mood"))
        }
        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.headers["HX-Location"].orEmpty().joinToString()).contains("catalog=letters@1.0.1")
            val inDraft = withMediator { ListAttributeDefinitions(TenantId(tenant.id), catalogKey = letters, catalogVersion = at(tenant, "1.0.1")).query() }.map { it.id.value }
            val current = withMediator { ListAttributeDefinitions(TenantId(tenant.id), catalogKey = letters).query() }.map { it.id.value }
            assertThat(inDraft).contains("mood", "tone")
            assertThat(current).doesNotContain("mood", "tone")
        }
    }

    @Test
    fun `New draft opens a draft and lands on it, and a number in use is refused on the field`() = fixture {
        lateinit var tenant: Tenant
        given {
            tenant = tenant("Drafts Create")
            released(tenant)
        }
        whenever {
            val dialog = getHtmx("/tenants/${tenant.id}/catalogs/letters/drafts/new").body!!
            assertThat(dialog).contains("data-testid=\"create-draft-from\"").contains("value=\"1.1.0\"")
            val refused = postForm("/tenants/${tenant.id}/catalogs/letters/drafts", mapOf("version" to "1.0.0"), target = "create-draft-form")
            assertThat(refused.statusCode.value()).isEqualTo(422)
            assertThat(refused.body).contains("data-testid=\"create-draft-error\"").contains("already has a version 1.0.0")
            postForm("/tenants/${tenant.id}/catalogs/letters/drafts", mapOf("version" to "1.0.5", "from" to "1.0.0"), target = "create-draft-form")
        }
        then {
            val response = result<ResponseEntity<String>>()
            // 1.0.5 is now the highest draft, the current one, so it is linked plainly.
            assertThat(drafts(tenant)).containsExactly("1.0.5", "1.0.1")
            assertThat(response.headers["HX-Location"]).containsExactly("/tenants/${tenant.id}/catalogs/letters/browse")
        }
    }

    @Test
    fun `a draft opened below the current one lands on its own page`() = fixture {
        lateinit var tenant: Tenant
        given {
            tenant = tenant("Drafts Create Lower")
            released(tenant)
            withMediator { CreateCatalogVersion(tenant.id, letters, "2.0.0").execute() }
        }
        whenever { postForm("/tenants/${tenant.id}/catalogs/letters/drafts", mapOf("version" to "1.1.0"), target = "create-draft-form") }
        then {
            assertThat(result<ResponseEntity<String>>().headers["HX-Location"]).containsExactly("/tenants/${tenant.id}/catalogs/letters@1.1.0/browse")
            assertThat(drafts(tenant)).containsExactly("2.0.0", "1.1.0", "1.0.1")
        }
    }

    @Test
    fun `Discard draft discards the chosen draft and lands on the current version`() = fixture {
        lateinit var tenant: Tenant
        given {
            tenant = tenant("Drafts Discard")
            twoDrafts(tenant)
        }
        whenever { postForm("/tenants/${tenant.id}/catalogs/letters@1.0.1/drafts/discard", emptyMap(), htmx = false) }
        then {
            val response = result<ResponseEntity<String>>()
            // A 303 to the catalog page at its current version; the test client may follow it.
            if (response.statusCode.is3xxRedirection) {
                assertThat(response.headers.location.toString()).endsWith("/tenants/${tenant.id}/catalogs/letters/browse")
            } else {
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.body).doesNotContain("data-testid=\"catalog-draft-picker\"")
            }
            assertThat(drafts(tenant)).containsExactly("2.0.0")
        }
    }

    @Test
    fun `discarding a draft that alone holds the tenant's default theme is refused on the catalog page`() = fixture {
        lateinit var tenant: Tenant
        given {
            tenant = tenant("Drafts Discard Held")
            twoDrafts(tenant)
            withMediator {
                CreateTheme(ThemeId(ThemeKey.of("seasonal"), catalogId(tenant)), "Seasonal", catalogVersion = at(tenant, "2.0.0")).execute()
                SetTenantDefaultTheme(tenant.id, ThemeKey.of("seasonal"), letters).execute()
            }
        }
        whenever { postForm("/tenants/${tenant.id}/catalogs/letters@2.0.0/drafts/discard", emptyMap(), htmx = false) }
        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("data-testid=\"catalog-next-release-error\"")
                .contains("the tenant&#39;s default theme (theme letters/seasonal)")
            assertThat(drafts(tenant)).containsExactly("2.0.0", "1.0.1")
        }
    }

    @Test
    fun `Renumber renumbers the chosen draft and lands on it under its new number`() = fixture {
        lateinit var tenant: Tenant
        given {
            tenant = tenant("Drafts Renumber")
            twoDrafts(tenant)
        }
        whenever {
            val dialog = getHtmx("/tenants/${tenant.id}/catalogs/letters@1.0.1/renumber").body!!
            assertThat(dialog).contains("value=\"1.0.1\"").contains("/tenants/${tenant.id}/catalogs/letters@1.0.1/renumber")
            postForm("/tenants/${tenant.id}/catalogs/letters@1.0.1/renumber", mapOf("to" to "1.0.2"))
        }
        then {
            assertThat(result<ResponseEntity<String>>().headers["HX-Location"]).containsExactly("/tenants/${tenant.id}/catalogs/letters@1.0.2/browse")
            assertThat(drafts(tenant)).containsExactly("2.0.0", "1.0.2")
        }
    }

    @Test
    fun `Release releases the chosen draft, not the current one`() = fixture {
        lateinit var tenant: Tenant
        given {
            tenant = tenant("Drafts Release Chosen")
            twoDrafts(tenant)
            withMediator { MarkCatalogReady(tenant.id, letters, catalogVersion = at(tenant, "1.0.1")).execute() }
        }
        whenever {
            val dialog = getHtmx("/tenants/${tenant.id}/catalogs/letters@1.0.1/release").body!!
            assertThat(dialog).contains("value=\"1.0.1\"").contains("Release draft v1.0.1")
            postForm("/tenants/${tenant.id}/catalogs/letters@1.0.1/release", mapOf("version" to "1.0.1"))
        }
        then {
            assertThat(result<ResponseEntity<String>>().headers["HX-Location"]).containsExactly("/tenants/${tenant.id}/catalogs/letters/browse")
            val versions = withMediator { ListCatalogVersions(tenant.id, letters).query() }.map { it.version to it.status }
            assertThat(versions).containsExactly(
                "2.0.0" to CatalogVersionStatus.DRAFT,
                "1.0.1" to CatalogVersionStatus.RELEASED,
                "1.0.0" to CatalogVersionStatus.RELEASED,
            )
            assertThat(withMediator { GetDocumentTemplate(invoice(tenant), catalogVersion = at(tenant, "2.0.0")).query() }!!.name).isEqualTo("Invoice two")
        }
    }

    @Test
    fun `a draft behind a release names it with its changes, and releases only once it is acknowledged`() = fixture {
        lateinit var tenant: Tenant
        given {
            tenant = tenant("Drafts Behind")
            twoDrafts(tenant)
            withMediator {
                UpdateTheme(brand(tenant), name = "Brand, fixed", catalogVersion = at(tenant, "1.0.1")).execute()
                MarkCatalogReady(tenant.id, letters, catalogVersion = at(tenant, "1.0.1")).execute()
                ReleaseCatalogVersion(tenant.id, letters, "1.0.1").execute()
                MarkCatalogReady(tenant.id, letters, catalogVersion = at(tenant, "2.0.0")).execute()
            }
        }
        whenever {
            val page = get("/tenants/${tenant.id}/catalogs/letters/browse").body!!
            assertThat(page).contains("data-testid=\"catalog-draft-behind\"").contains("data-testid=\"catalog-draft-behind-list\"")
            assertThat(page).contains("theme/brand modified")
            val dialog = getHtmx("/tenants/${tenant.id}/catalogs/letters/release").body!!
            assertThat(dialog).contains("data-testid=\"release-acknowledge-behind\"").contains("name=\"acknowledgedBehind\"").contains("value=\"1.0.1\"")
            val refused = postForm("/tenants/${tenant.id}/catalogs/letters/release", mapOf("version" to "2.0.0"))
            assertThat(refused.body).contains("Confirm that this draft may be released without v1.0.1")
            assertThat(withMediator { ListCatalogVersions(tenant.id, letters, CatalogVersionStatus.DRAFT).query() }.map { it.version }).containsExactly("2.0.0")
            postForm("/tenants/${tenant.id}/catalogs/letters/release", mapOf("version" to "2.0.0", "acknowledgedBehind" to "1.0.1"))
        }
        then {
            assertThat(result<ResponseEntity<String>>().headers["HX-Location"]).containsExactly("/tenants/${tenant.id}/catalogs/letters/browse")
            assertThat(drafts(tenant)).containsExactly("2.0.1")
        }
    }

    @Test
    fun `a catalog without a draft offers to open one from its latest release`() = fixture {
        lateinit var tenant: Tenant
        given {
            tenant = tenant("Drafts Empty")
            released(tenant)
            withMediator { DeleteCatalogVersion(tenant.id, letters, "1.0.1").execute() }
        }
        whenever {
            val page = get("/tenants/${tenant.id}/catalogs/letters/browse")
            assertThat(page.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(page.body).contains("data-testid=\"catalog-draft-open\"").contains("Open draft v1.0.1 from v1.0.0")
            assertThat(page.body).doesNotContain("data-testid=\"catalog-release-open\"").doesNotContain("data-testid=\"catalog-mark-ready\"")
            postForm("/tenants/${tenant.id}/catalogs/letters/drafts", mapOf("version" to "1.0.1", "from" to "1.0.0"), htmx = false)
        }
        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode.is3xxRedirection || response.statusCode == HttpStatus.OK).isTrue()
            assertThat(drafts(tenant)).containsExactly("1.0.1")
            assertThat(withMediator { GetTheme(brand(tenant)).query() }!!.name).isEqualTo("Brand")
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun get(url: String): ResponseEntity<String> = restTemplate.getForEntity(url, String::class.java)

    private fun getHtmx(url: String): ResponseEntity<String> = restTemplate.exchange(
        url,
        HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders().apply { set("HX-Request", "true") }),
        String::class.java,
    )

    private fun send(method: HttpMethod, url: String, json: String): ResponseEntity<String> = restTemplate.exchange(
        url,
        method,
        HttpEntity(json, HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }),
        String::class.java,
    )

    private fun postForm(url: String, fields: Map<String, String>, target: String? = null, htmx: Boolean = true): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            if (htmx) set("HX-Request", "true")
            if (target != null) set("HX-Target", target)
        }
        val form = LinkedMultiValueMap<String, String>().apply { fields.forEach { (k, v) -> add(k, v) } }
        return restTemplate.postForEntity(url, HttpEntity(form, headers), String::class.java)
    }
}
