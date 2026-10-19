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
import app.epistola.suite.catalog.versions.CreateCatalogDraft
import app.epistola.suite.catalog.versions.DraftRef
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
import app.epistola.suite.templates.queries.variants.GetVariantSummaries
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.SetTenantDefaultTheme
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.themes.queries.GetTheme
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
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
import java.util.UUID

/**
 * The drafts of a catalog in the UI (contract ADR 0002, amendment 2): long-term work travels in the
 * catalog's path segment (`letters@<draft id>`), the plain segment is the next version, the catalog
 * page and the content pages work on the draft the segment names, and only the next version is
 * published.
 */
class CatalogDraftsHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jdbi: Jdbi

    private val letters = CatalogKey.of("letters")

    private fun catalogId(tenant: Tenant) = CatalogId(letters, TenantId(tenant.id))

    private fun brand(tenant: Tenant) = ThemeId(ThemeKey.of("brand"), catalogId(tenant))

    private fun invoice(tenant: Tenant) = TemplateId(TemplateKey.of("invoice"), catalogId(tenant))

    private fun at(tenant: Tenant, draft: DraftRef) = CatalogVersionRef(tenant.id, letters, draft)

    private fun next(tenant: Tenant) = at(tenant, DraftRef.Next)

    /** `letters` (brand theme, invoice template) released as 1.0.0; its next version continues on it. */
    private fun released(tenant: Tenant) = withMediator {
        CreateCatalog(tenantKey = tenant.id, id = letters, name = "Letters").execute()
        CreateTheme(brand(tenant), "Brand").execute()
        CreateDocumentTemplate(invoice(tenant), "Invoice").execute().withRequiredDataExample()
        MarkCatalogReady(tenant.id, letters).execute()
        ReleaseCatalogVersion(tenant.id, letters, "1.0.0").execute()
    }

    /** [released], then long-term work "Redesign", and the invoice named differently in each; its id. */
    private fun withRedesign(tenant: Tenant): UUID {
        released(tenant)
        return withMediator {
            val work = requireNotNull(CreateCatalogDraft(tenant.id, letters, "Redesign").execute().draftId)
            UpdateDocumentTemplate(invoice(tenant), name = "Invoice, fixed", catalogVersion = next(tenant)).execute()
            UpdateDocumentTemplate(invoice(tenant), name = "Invoice redesigned", catalogVersion = at(tenant, DraftRef.Id(work))).execute()
            work
        }
    }

    private fun drafts(tenant: Tenant) = withMediator { ListCatalogVersions(tenant.id, letters, CatalogVersionStatus.DRAFT).query() }.map { it.label }

    @Test
    fun `the next version's page says it is numbered when published, and the Versions page offers long-term work`() = fixture {
        lateinit var tenant: Tenant
        given {
            tenant = tenant("Drafts One")
            released(tenant)
        }
        whenever { get("/tenants/${tenant.id}/catalogs/letters/draft") to get("/tenants/${tenant.id}/catalogs/letters/versions") }
        then {
            val (draft, catalog) = result<Pair<ResponseEntity<String>, ResponseEntity<String>>>()
            assertThat(draft.body!!).contains("has no number until it is published").doesNotContain("catalog-renumber-open")
            assertThat(catalog.body!!).contains("data-testid=\"catalog-draft-new\"").contains("Start long-term work")
        }
    }

    @Test
    fun `with long-term work the Versions page lists both, and a draft's page works on the one its segment names`() = fixture {
        lateinit var tenant: Tenant
        lateinit var work: UUID
        given {
            tenant = tenant("Drafts Picker")
            work = withRedesign(tenant)
        }
        whenever {
            val catalog = get("/tenants/${tenant.id}/catalogs/letters/versions").body!!
            // The next version is linked plainly, long-term work by its id.
            assertThat(catalog).contains("href=\"/tenants/${tenant.id}/catalogs/letters/draft\"")
            assertThat(catalog).contains("href=\"/tenants/${tenant.id}/catalogs/letters@$work/draft\"").contains(">Redesign<")
            get("/tenants/${tenant.id}/catalogs/letters@$work/draft")
        }
        then {
            val body = result<ResponseEntity<String>>().body!!
            assertThat(body).contains("<span id=\"page-title-text\">Redesign</span>")
            assertThat(body).contains("data-testid=\"catalog-long-term-not-published\"")
            // Its resources link into the draft, and its actions act on it.
            assertThat(body).contains("/tenants/${tenant.id}/catalogs/letters@$work/templates/invoice")
            assertThat(body).contains("/tenants/${tenant.id}/catalogs/letters@$work/drafts/discard")
            assertThat(body).`as`("long-term work is not published").doesNotContain("data-testid=\"catalog-release-open\"")
        }
    }

    @Test
    fun `a draft the catalog does not have is not found, and a number names no draft`() = fixture {
        lateinit var tenant: Tenant
        given {
            tenant = tenant("Drafts Unknown")
            released(tenant)
        }
        whenever { get("/tenants/${tenant.id}/catalogs/letters@${UUID.randomUUID()}/templates/invoice") to get("/tenants/${tenant.id}/catalogs/letters@1.0.1/templates/invoice") }
        then {
            val (unknown, number) = result<Pair<ResponseEntity<String>, ResponseEntity<String>>>()
            assertThat(unknown.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            assertThat(number.statusCode).`as`("a draft has no number: an old link finds nothing").isEqualTo(HttpStatus.NOT_FOUND)
        }
    }

    @Test
    fun `the template page in long-term work shows its template, under the banner, and links stay in it`() = fixture {
        lateinit var tenant: Tenant
        lateinit var work: UUID
        given {
            tenant = tenant("Drafts Template")
            work = withRedesign(tenant)
        }
        whenever { get("/tenants/${tenant.id}/catalogs/letters@$work/templates/invoice") }
        then {
            val body = result<ResponseEntity<String>>().body!!
            assertThat(body).contains("Invoice redesigned").doesNotContain("Invoice, fixed")
            assertThat(body).contains("data-testid=\"catalog-long-term-banner\"").contains("data-draft=\"$work\"")
            assertThat(body).contains("/tenants/${tenant.id}/catalogs/letters@$work/templates/invoice/settings")
            assertThat(body).contains("/tenants/${tenant.id}/catalogs/letters@$work/templates/invoice/variants/initial/editor")
        }
    }

    @Test
    fun `switching versions lands on the same page in the other version`() {
        lateinit var tenant: Tenant
        lateinit var work: UUID
        fixture {
            given {
                tenant = tenant("Drafts Switch")
                work = withRedesign(tenant)
            }
        }
        fun switch(to: String, from: String?, htmx: Boolean = false): ResponseEntity<String> = restTemplate.exchange(
            // A URI, so the encoded `from` is not encoded twice.
            java.net.URI.create(restTemplate.rootUri + "/tenants/${tenant.id}/catalogs/$to/switch" + (from?.let { "?from=" + java.net.URLEncoder.encode(it, Charsets.UTF_8) } ?: "")),
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { if (htmx) set("HX-Request", "true") }),
            String::class.java,
        )

        val boosted = switch("letters@$work", "/tenants/${tenant.id}/catalogs/letters/templates/invoice", htmx = true)
        assertThat(boosted.headers.getFirst("HX-Redirect")).isEqualTo("/tenants/${tenant.id}/catalogs/letters@$work/templates/invoice")
        assertThat(switch("letters", "/tenants/${tenant.id}/catalogs/letters@$work/templates", htmx = true).headers.getFirst("HX-Redirect"))
            .isEqualTo("/tenants/${tenant.id}/catalogs/letters/templates")
        assertThat(switch("letters@$work", "https://evil.example/", htmx = true).headers.getFirst("HX-Redirect"))
            .`as`("never anywhere else: the version's overview").isEqualTo("/tenants/${tenant.id}/catalogs/letters@$work/draft")
        assertThat(switch("letters@$work", "/tenants/${tenant.id}/catalogs/letters/templates/invoice").statusCode.is2xxSuccessful)
            .`as`("without htmx, a redirect the browser follows").isTrue()

        // A page the other version does not have leads to that section of it, not a dead end.
        val missing = restTemplate.exchange(
            "/tenants/${tenant.id}/catalogs/letters@$work/templates/no-such-template",
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { accept = listOf(MediaType.TEXT_HTML) }),
            String::class.java,
        )
        assertThat(missing.statusCode.value()).isEqualTo(404)
        assertThat(missing.body).contains("Not in this version").contains("Templates in this version")
            .contains("/tenants/${tenant.id}/catalogs/letters@$work/templates")
    }

    @Test
    fun `the plain template page is the next version, and offers long-term work once there is some`() = fixture {
        lateinit var tenant: Tenant
        lateinit var several: String
        lateinit var work: UUID
        given {
            tenant = tenant("Drafts Template Plain")
            released(tenant)
        }
        whenever {
            val one = get("/tenants/${tenant.id}/catalogs/letters/templates/invoice").body!!
            work = withMediator { requireNotNull(CreateCatalogDraft(tenant.id, letters, "Redesign").execute().draftId) }
            withMediator { UpdateDocumentTemplate(invoice(tenant), name = "Invoice redesigned", catalogVersion = at(tenant, DraftRef.Id(work))).execute() }
            several = get("/tenants/${tenant.id}/catalogs/letters/templates/invoice").body!!
            one
        }
        then {
            // The context bar names the version; its menu offers the long-term work.
            assertThat(result<String>()).contains("data-testid=\"ws-crumb-version\"").contains(">Next version<")
                .doesNotContain("/templates/letters@")
            assertThat(several).doesNotContain("Invoice redesigned").contains("data-testid=\"ws-version-link\"").contains("/catalogs/letters@$work/switch")
        }
    }

    @Test
    fun `the editor saves the variant into the draft its route names`() = fixture {
        lateinit var tenant: Tenant
        lateinit var work: UUID
        given {
            tenant = tenant("Drafts Save")
            work = withRedesign(tenant)
        }
        whenever {
            val variant = VariantId(VariantKey.INITIAL, invoice(tenant))
            val model = withMediator { GetVariantContent(variant, catalogVersion = at(tenant, DraftRef.Id(work))).query() }!!
            val json = objectMapper.writeValueAsString(mapOf("templateModel" to model.copy(documentStylesOverride = mapOf("color" to "#123456"))))
            send(HttpMethod.PUT, "/tenants/${tenant.id}/catalogs/letters@$work/templates/invoice/variants/initial/content", json)
        }
        then {
            assertThat(result<ResponseEntity<String>>().statusCode).isEqualTo(HttpStatus.OK)
            val variant = VariantId(VariantKey.INITIAL, invoice(tenant))
            assertThat(withMediator { GetVariantContent(variant, catalogVersion = at(tenant, DraftRef.Id(work))).query() }!!.documentStylesOverride).containsEntry("color", "#123456")
            assertThat(withMediator { GetVariantContent(variant, catalogVersion = next(tenant)).query() }!!.documentStylesOverride.orEmpty()).doesNotContainKey("color")
        }
    }

    @Test
    fun `a theme saved from its page in long-term work changes only that work`() = fixture {
        lateinit var tenant: Tenant
        lateinit var work: UUID
        given {
            tenant = tenant("Drafts Theme")
            work = withRedesign(tenant)
        }
        whenever {
            val page = get("/tenants/${tenant.id}/catalogs/letters@$work/themes/brand").body!!
            assertThat(page).contains("\"catalogSegment\": \"letters@$work\"")
            send(HttpMethod.PATCH, "/tenants/${tenant.id}/catalogs/letters@$work/themes/brand", """{"name":"Brand redesigned"}""")
        }
        then {
            assertThat(result<ResponseEntity<String>>().statusCode).isEqualTo(HttpStatus.OK)
            assertThat(withMediator { GetTheme(brand(tenant), catalogVersion = at(tenant, DraftRef.Id(work))).query() }!!.name).isEqualTo("Brand redesigned")
            assertThat(withMediator { GetTheme(brand(tenant), catalogVersion = next(tenant)).query() }!!.name).isEqualTo("Brand")
        }
    }

    @Test
    fun `a stencil page in long-term work shows the work's stencil`() = fixture {
        lateinit var tenant: Tenant
        lateinit var work: UUID
        given {
            tenant = tenant("Drafts Stencil")
            work = withRedesign(tenant)
            withMediator { CreateStencil(StencilId(StencilKey.of("footer"), catalogId(tenant)), "Footer", catalogVersion = at(tenant, DraftRef.Id(work))).execute() }
        }
        whenever {
            val inWork = get("/tenants/${tenant.id}/catalogs/letters@$work/stencils/footer")
            val current = get("/tenants/${tenant.id}/catalogs/letters/stencils/footer")
            inWork to current
        }
        then {
            val (inWork, current) = result<Pair<ResponseEntity<String>, ResponseEntity<String>>>()
            assertThat(inWork.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(inWork.body).contains("/tenants/${tenant.id}/catalogs/letters@$work/stencils/footer/ready")
            assertThat(current.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        }
    }

    @Test
    fun `the attributes list filtered to long-term work lists its attributes, and creating there lands in it`() = fixture {
        lateinit var tenant: Tenant
        lateinit var work: UUID
        given {
            tenant = tenant("Drafts Attributes")
            work = withRedesign(tenant)
            withMediator { CreateAttributeDefinition(AttributeId(AttributeKey.of("tone"), catalogId(tenant)), "Tone", catalogVersion = at(tenant, DraftRef.Id(work))).execute() }
        }
        whenever {
            val list = get("/tenants/${tenant.id}/catalogs/letters@$work/attributes").body!!
            assertThat(list).contains("Tone")
            assertThat(get("/tenants/${tenant.id}/catalogs/letters/attributes").body!!).doesNotContain(">Tone<")
            val dialog = getHtmx("/tenants/${tenant.id}/attributes/new?catalog=letters@$work").body!!
            assertThat(dialog).contains("value=\"letters@$work\"")
            postForm("/tenants/${tenant.id}/attributes", mapOf("catalog" to "letters@$work", "slug" to "mood", "displayName" to "Mood"))
        }
        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.headers["HX-Location"].orEmpty().joinToString()).contains("/catalogs/letters@$work/attributes")
            val inWork = withMediator { ListAttributeDefinitions(TenantId(tenant.id), catalogKey = letters, catalogVersion = at(tenant, DraftRef.Id(work))).query() }.map { it.id.value }
            val current = withMediator { ListAttributeDefinitions(TenantId(tenant.id), catalogKey = letters).query() }.map { it.id.value }
            assertThat(inWork).contains("mood", "tone")
            assertThat(current).doesNotContain("mood", "tone")
        }
    }

    @Test
    fun `Start long-term work opens it and lands on it, starting from a published version when asked, and a name in use is refused on the field`() = fixture {
        lateinit var tenant: Tenant
        given {
            tenant = tenant("Drafts Create")
            released(tenant)
        }
        whenever {
            val dialog = getHtmx("/tenants/${tenant.id}/catalogs/letters/drafts/new").body!!
            assertThat(dialog).contains("data-testid=\"create-draft-name\"").contains("data-testid=\"create-draft-from\"").contains("value=\"1.0.0\"")
            assertThat(getHtmx("/tenants/${tenant.id}/catalogs/letters/drafts/new?from=1.0.0").body!!).`as`("opened on a published version")
                .contains("value=\"1.0.0\" selected")
            postForm("/tenants/${tenant.id}/catalogs/letters/drafts", mapOf("name" to "Redesign"), target = "create-draft-form")
            val refused = postForm("/tenants/${tenant.id}/catalogs/letters/drafts", mapOf("name" to "redesign"), target = "create-draft-form")
            assertThat(refused.statusCode.value()).isEqualTo(422)
            assertThat(refused.body).contains("data-testid=\"create-draft-error\"").contains("Other work is called that already")
            postForm("/tenants/${tenant.id}/catalogs/letters/drafts", mapOf("name" to "From one", "from" to "1.0.0"), target = "create-draft-form")
        }
        then {
            val response = result<ResponseEntity<String>>()
            val fromOne = withMediator { ListCatalogVersions(tenant.id, letters).query() }.single { it.name == "From one" }
            assertThat(fromOne.base).isEqualTo("1.0.0")
            assertThat(drafts(tenant)).containsExactly("Next version", "Redesign", "From one")
            assertThat(response.headers["HX-Location"]).containsExactly("/tenants/${tenant.id}/catalogs/letters@${fromOne.draftId}/draft")
        }
    }

    @Test
    fun `discarding long-term work removes it and lands on the catalog`() = fixture {
        lateinit var tenant: Tenant
        lateinit var work: UUID
        given {
            tenant = tenant("Drafts Discard")
            work = withRedesign(tenant)
        }
        whenever { postForm("/tenants/${tenant.id}/catalogs/letters@$work/drafts/discard", emptyMap(), htmx = false) }
        then {
            val response = result<ResponseEntity<String>>()
            // A 303 to the catalog page; the test client may follow it.
            if (response.statusCode.is3xxRedirection) {
                assertThat(response.headers.location.toString()).endsWith("/tenants/${tenant.id}/catalogs/letters/browse")
            } else {
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            }
            assertThat(drafts(tenant)).containsExactly("Next version")
            assertThat(withMediator { GetDocumentTemplate(invoice(tenant)).query() }!!.name).`as`("the next version is untouched").isEqualTo("Invoice, fixed")
        }
    }

    @Test
    fun `discarding the next version throws its changes away and starts it again on the latest release`() = fixture {
        lateinit var tenant: Tenant
        given {
            tenant = tenant("Drafts Discard Next")
            withRedesign(tenant)
        }
        whenever { postForm("/tenants/${tenant.id}/catalogs/letters/drafts/discard", emptyMap(), htmx = false) }
        then {
            assertThat(drafts(tenant)).containsExactly("Next version", "Redesign")
            assertThat(withMediator { GetDocumentTemplate(invoice(tenant)).query() }!!.name).isEqualTo("Invoice")
        }
    }

    @Test
    fun `discarding the next version while it alone holds the tenant's default theme is refused on its page`() = fixture {
        lateinit var tenant: Tenant
        given {
            tenant = tenant("Drafts Discard Held")
            withRedesign(tenant)
            withMediator {
                CreateTheme(ThemeId(ThemeKey.of("seasonal"), catalogId(tenant)), "Seasonal").execute()
                SetTenantDefaultTheme(tenant.id, ThemeKey.of("seasonal"), letters).execute()
            }
        }
        whenever { postForm("/tenants/${tenant.id}/catalogs/letters/drafts/discard", emptyMap(), htmx = false) }
        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("data-testid=\"catalog-next-release-error\"")
                .contains("the tenant&#39;s default theme (theme letters/seasonal)")
            assertThat(withMediator { GetTheme(ThemeId(ThemeKey.of("seasonal"), catalogId(tenant))).query() }).`as`("nothing was discarded").isNotNull
        }
    }

    @Test
    fun `publishing is refused for long-term work, leaving it as it was, and the next version is published as its changes need`() = fixture {
        lateinit var tenant: Tenant
        lateinit var work: UUID
        given {
            tenant = tenant("Drafts Release")
            work = withRedesign(tenant)
        }
        whenever {
            val refused = postForm("/tenants/${tenant.id}/catalogs/letters@$work/release", mapOf("version" to "2.0.0"))
            assertThat(refused.body).contains("Long-term work is not published")
            assertThat(withMediator { GetVariantSummaries(invoice(tenant), catalogVersion = at(tenant, DraftRef.Id(work))).query() }.map { it.ready })
                .`as`("not marked ready by the refused publish").containsOnly(false)
            val dialog = getHtmx("/tenants/${tenant.id}/catalogs/letters/release").body!!
            assertThat(dialog).contains("Publish Letters").contains("value=\"1.0.1\"")
            postForm("/tenants/${tenant.id}/catalogs/letters/release", mapOf("version" to "1.0.1"))
        }
        then {
            assertThat(result<ResponseEntity<String>>().headers["HX-Location"]).containsExactly("/tenants/${tenant.id}/catalogs/letters/browse")
            val versions = withMediator { ListCatalogVersions(tenant.id, letters).query() }.map { it.label to it.status }
            assertThat(versions).containsExactly(
                "Next version" to CatalogVersionStatus.DRAFT,
                "Redesign" to CatalogVersionStatus.DRAFT,
                "v1.0.1" to CatalogVersionStatus.RELEASED,
                "v1.0.0" to CatalogVersionStatus.RELEASED,
            )
            assertThat(withMediator { GetDocumentTemplate(invoice(tenant), catalogVersion = at(tenant, DraftRef.Id(work))).query() }!!.name).isEqualTo("Invoice redesigned")
        }
    }

    @Test
    fun `long-term work behind a release names it with its changes`() = fixture {
        lateinit var tenant: Tenant
        lateinit var work: UUID
        given {
            tenant = tenant("Drafts Behind")
            work = withRedesign(tenant)
            withMediator {
                UpdateTheme(brand(tenant), name = "Brand, fixed").execute()
                MarkCatalogReady(tenant.id, letters).execute()
                ReleaseCatalogVersion(tenant.id, letters).execute()
            }
        }
        whenever { get("/tenants/${tenant.id}/catalogs/letters@$work/draft").body!! }
        then {
            val page = result<String>()
            assertThat(page).contains("data-testid=\"catalog-draft-behind-list\"")
            assertThat(page).contains("is not in this draft").contains(": it changed").contains("brand")
        }
    }

    @Test
    fun `a catalog without a next version offers to start it from its latest release`() = fixture {
        lateinit var tenant: Tenant
        given {
            tenant = tenant("Drafts Empty")
            released(tenant)
            // No command leaves a catalog without a next version any more; one whose drafts were all
            // discarded before ADR 0002 amendment 2 is the state this page offers to repair.
            jdbi.useHandle<Exception> {
                it.createUpdate("DELETE FROM catalog_versions WHERE tenant_key = :t AND is_next").bind("t", tenant.id).execute()
            }
        }
        whenever {
            val page = get("/tenants/${tenant.id}/catalogs/letters/browse")
            assertThat(page.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(page.body).contains("data-testid=\"catalog-draft-open\"").contains("Start the next version")
            assertThat(page.body).doesNotContain("data-testid=\"catalog-release-open\"").doesNotContain("data-testid=\"catalog-mark-ready\"")
            postForm("/tenants/${tenant.id}/catalogs/letters/drafts", emptyMap(), htmx = false)
        }
        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode.is3xxRedirection || response.statusCode == HttpStatus.OK).isTrue()
            assertThat(drafts(tenant)).containsExactly("Next version")
            assertThat(withMediator { GetTheme(brand(tenant)).query() }!!.name).isEqualTo("Brand")
        }
    }

    @Test
    fun `making long-term work the next version asks to confirm what was published meanwhile, and keeps the old one by name`() = fixture {
        lateinit var tenant: Tenant
        lateinit var work: UUID
        given {
            tenant = tenant("Drafts Promote")
            work = withRedesign(tenant)
            withMediator {
                MarkCatalogReady(tenant.id, letters).execute()
                ReleaseCatalogVersion(tenant.id, letters).execute() // 1.0.1, which the work does not contain
            }
        }
        whenever {
            val dialog = getHtmx("/tenants/${tenant.id}/catalogs/letters@$work/promote").body!!
            assertThat(dialog).contains("data-testid=\"promote-behind\"").contains("name=\"acknowledgedBehind\" value=\"1.0.1\" required")
                .contains("value=\"Next version before Redesign\"")
            val refused = postForm("/tenants/${tenant.id}/catalogs/letters@$work/promote", mapOf("previous" to "keep", "keepAs" to "Old"))
            assertThat(refused.body).contains("Confirm each version published since this work started: v1.0.1")
            postForm("/tenants/${tenant.id}/catalogs/letters@$work/promote", mapOf("previous" to "keep", "keepAs" to "Old", "acknowledgedBehind" to "1.0.1"))
        }
        then {
            assertThat(result<ResponseEntity<String>>().headers["HX-Location"]).containsExactly("/tenants/${tenant.id}/catalogs/letters/browse")
            assertThat(drafts(tenant)).containsExactly("Next version", "Old")
            assertThat(withMediator { GetDocumentTemplate(invoice(tenant)).query() }!!.name).isEqualTo("Invoice redesigned")
        }
    }

    @Test
    fun `a release exports with what it uses as one bundle`() = fixture {
        lateinit var tenant: Tenant
        given {
            tenant = tenant("Drafts Fat Export")
            released(tenant)
        }
        whenever { restTemplate.getForEntity("/tenants/${tenant.id}/catalogs/letters/export?version=1.0.0&includeDependencies=true", ByteArray::class.java) }
        then {
            val response = result<ResponseEntity<ByteArray>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.headers.getFirst("Content-Disposition")).contains("letters-1.0.0-with-dependencies.zip")
            assertThat(app.epistola.suite.catalog.CatalogBundle.isBundle(response.body!!)).isTrue()
            assertThat(get("/tenants/${tenant.id}/catalogs/letters/releases/1.0.0").body!!).contains("data-testid=\"release-export-with-dependencies\"")
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
