// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.system.systemCatalog
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
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
import tools.jackson.databind.ObjectMapper

/**
 * A picker offers what the catalog version can use (contract ADR 0003): its own catalog's resources
 * and those of the catalogs it declares, at the version each declaration names; never a catalog of
 * the tenant it does not declare. A new catalog declares the system catalog, so its fonts, locales
 * and themes are offered from the start.
 */
class VisibleCatalogPickersHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val letters = CatalogKey.of("letters")
    private val shared = CatalogKey.of("shared")

    /** `letters` with an invoice; `shared` with a brand theme and an audience attribute, released as 1.0.0 but not declared. */
    private fun setUp(name: String): TenantKey = withMediator {
        val tenant = createTenant(name).id
        CreateCatalog(tenant, letters, "Letters").execute()
        CreateCatalog(tenant, shared, "Shared").execute()
        CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), testCatalog(tenant, letters)), "Invoice", catalogVersion = null).execute()
        CreateTheme(ThemeId(ThemeKey.of("brand"), testCatalog(tenant, shared)), "Shared brand", catalogVersion = null).execute()
        CreateAttributeDefinition(AttributeId(AttributeKey.of("audience"), testCatalog(tenant, shared)), "Audience", catalogVersion = null).execute()
        MarkCatalogReady(testCatalog(tenant, shared), catalogVersion = null).execute()
        ReleaseCatalogDraft(testCatalog(tenant, shared), "1.0.0", catalogVersion = null).execute()
        tenant
    }

    /** Declares, in `letters`, the system catalog as it is, and [plus] `shared` at 1.0.0. */
    private fun declare(tenant: TenantKey, plus: Boolean, system: Boolean = true) = withMediator {
        val current = ListCatalogDependencies(testCatalog(tenant, letters), catalogVersion = null).query()
            .filter { system && it.catalogKey == "system" }
            .map { DeclaredCatalogDependency(it.registry, it.namespace, it.catalogKey, it.releaseVersion) }
        val sharedCatalog = GetCatalog(testCatalog(tenant, shared)).query()!!
        val added = if (plus) listOf(DeclaredCatalogDependency(sharedCatalog.registry, sharedCatalog.namespace, shared.value, "1.0.0")) else emptyList()
        UpdateCatalogDependencies(testCatalog(tenant, letters), current + added, catalogVersion = null).execute()
    }

    private fun get(path: String, htmx: Boolean = false): String {
        val headers = HttpHeaders().apply { if (htmx) set("HX-Request", "true") }
        val response = restTemplate.exchange(path, HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return response.body!!
    }

    private fun templatePage(tenant: TenantKey, tab: String) = get("/tenants/${tenant.value}/catalogs/${testCatalog(tenant, letters).address}/templates/invoice$tab")

    private fun fonts(tenant: TenantKey, catalog: CatalogKey = letters) = objectMapper.readTree(get("/tenants/${tenant.value}/fonts/search?catalog=${testCatalog(tenant, catalog).address}")).values()

    /** The `<option>` for [value] in [page], its attributes included. */
    private fun option(page: String, value: String): String = Regex("""<option[^>]*value="${Regex.escape(value)}"[^>]*>""").find(page)?.value
        ?: throw AssertionError("No option $value")

    @Test
    fun `the template theme picker offers the system catalog's themes, and an undeclared catalog's apart, to be declared first`() {
        val tenant = setUp("theme-picker")
        val brand = "${testCatalog(tenant, shared).address}/brand"

        val before = templatePage(tenant, "/settings")
        assertThat(option(before, "${systemCatalog(tenant).address}/default")).doesNotContain("data-declares")
        assertThat(option(before, brand)).contains("data-declares=\"${testCatalog(tenant, shared).address}\"")
        assertThat(before).contains("shared (adds a dependency)").contains("data-declare-url=\"/tenants/${tenant.value}/catalogs/${testCatalog(tenant, letters).address}/dependencies/declare\"")

        declare(tenant, plus = true)

        assertThat(option(templatePage(tenant, "/settings"), brand)).doesNotContain("data-declares")
    }

    @Test
    fun `the variant attribute picker offers the system locale, and an undeclared catalog's attributes apart, to be declared first`() {
        val tenant = setUp("attribute-picker")

        val before = templatePage(tenant, "")
        assertThat(before).contains("attr_system.locale")
        assertThat(option(before, "system.locale")).doesNotContain("data-declares")
        assertThat(option(before, "shared.audience")).contains("data-declares=\"${testCatalog(tenant, shared).address}\"")

        declare(tenant, plus = true)

        assertThat(option(templatePage(tenant, ""), "shared.audience")).doesNotContain("data-declares")
    }

    @Test
    fun `the editor's fonts are those of the declared system release, served from that release`() {
        val tenant = setUp("font-picker")
        val release = withMediator { GetCatalog(systemCatalog(tenant)).query()!!.latestReleaseVersion!! }

        val inter = fonts(tenant).single { it["slug"].asString() == "inter" }

        assertThat(inter["catalogKey"].asString()).isEqualTo("system")
        val face = inter["css"]["faces"].values().first { it["weight"].asInt() == 400 && !it["italic"].asBoolean() }["url"].asString()
        assertThat(face).isEqualTo("/tenants/${tenant.value}/catalogs/${systemCatalog(tenant).address}@$release/fonts/inter/400/false/content")
        assertThat(restTemplate.getForEntity(face, ByteArray::class.java).statusCode).`as`("the face is served from the release").isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `a catalog that does not declare the system catalog is offered its fonts only apart, to be declared first`() {
        val tenant = setUp("font-undeclared")

        declare(tenant, plus = false, system = false)

        val system = fonts(tenant).filter { it["catalogKey"].asString() == "system" }
        assertThat(system).isNotEmpty()
        assertThat(system.map { it["declared"].asBoolean() }).containsOnly(false)
        assertThat(system.map { it["catalog"].asString() }).containsOnly(systemCatalog(tenant).address)
    }

    @Test
    fun `a release, which never changes, is offered nothing of a catalog it does not declare`() {
        val tenant = setUp("font-release")
        declare(tenant, plus = false, system = false)
        withMediator {
            MarkCatalogReady(testCatalog(tenant, letters), catalogVersion = null).execute()
            ReleaseCatalogDraft(testCatalog(tenant, letters), "1.0.0", catalogVersion = null).execute()
        }

        val released = objectMapper.readTree(get("/tenants/${tenant.value}/fonts/search?catalog=${testCatalog(tenant, letters).address}@1.0.0")).values()

        assertThat(released.map { it["catalogKey"].asString() }).doesNotContain("system")
    }

    @Test
    fun `declaring a catalog from a picker adds it once, and the picker then offers its resources as usable`() {
        val tenant = setUp("declare-route")
        val url = "/tenants/${tenant.value}/catalogs/${testCatalog(tenant, letters).address}/dependencies/declare"
        fun post(): String {
            val form = LinkedMultiValueMap<String, String>().apply { add("dependency", testCatalog(tenant, shared).address) }
            val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }
            val response = restTemplate.postForEntity(url, HttpEntity(form, headers), String::class.java)
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            return response.body!!
        }

        assertThat(objectMapper.readTree(post())["declared"].values().map { it.asString() }).containsExactly(shared.value)
        assertThat(objectMapper.readTree(post())["declared"].values()).isEmpty()

        assertThat(withMediator { ListCatalogDependencies(testCatalog(tenant, letters), catalogVersion = null).query() }.map { it.catalogKey }).contains(shared.value)
        val after = templatePage(tenant, "/settings")
        assertThat(option(after, "${testCatalog(tenant, shared).address}/brand")).doesNotContain("data-declares")
        assertThat(after).doesNotContain("shared (adds a dependency)")
    }

    @Test
    fun `the editor's stencil and image pickers mark what they offer of catalogs the template does not declare`() {
        val tenant = setUp("editor-pickers")
        withMediator {
            CreateStencil(StencilId(StencilKey.of("header"), testCatalog(tenant, shared)), "Shared header", catalogVersion = null).execute()
        }

        val stencils = objectMapper.readTree(get("/tenants/${tenant.value}/stencils/search?in=${testCatalog(tenant, letters).address}"))["items"].values()
        val header = stencils.single { it["id"].asString() == "header" }
        assertThat(header["declared"].asBoolean()).isFalse()
        assertThat(header["catalog"].asString()).isEqualTo(testCatalog(tenant, shared).address)

        val catalogs = objectMapper.readTree(get("/tenants/${tenant.value}/images/catalogs?for=${testCatalog(tenant, letters).address}")).values()
        assertThat(catalogs.associate { it["key"].asString() to it["declared"].asBoolean() })
            .containsEntry(testCatalog(tenant, letters).address, true)
            .containsEntry(systemCatalog(tenant).address, true)
            .containsEntry(testCatalog(tenant, shared).address, false)
    }

    @Test
    fun `an attribute's code list picker offers an undeclared catalog's code lists apart, to be declared first`() {
        val tenant = setUp("code-list-picker")
        withMediator {
            CreateCodeList(CodeListId(CodeListKey.of("languages"), testCatalog(tenant, shared)), "Languages", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("nl", "Nederlands")), catalogVersion = null).execute()
            CreateAttributeDefinition(AttributeId(AttributeKey.of("tone"), testCatalog(tenant, letters)), "Tone", catalogVersion = null).execute()
        }

        val dialog = get("/tenants/${tenant.value}/catalogs/${testCatalog(tenant, letters).address}/attributes/tone/edit", htmx = true)

        assertThat(option(dialog, "${testCatalog(tenant, shared).address}/languages")).contains("data-declares=\"${testCatalog(tenant, shared).address}\"")
    }

    @Test
    fun `the catalog default theme picker offers the system catalog's themes and a declared catalog's`() {
        val tenant = setUp("default-theme-picker")
        declare(tenant, plus = true)

        val page = get("/tenants/${tenant.value}/catalogs/${testCatalog(tenant, letters).address}/theme-settings", htmx = true)

        assertThat(page).contains("Shared brand (shared)")
        assertThat(page).contains("(system)")
        assertThat(page).doesNotContain("adds a dependency")
    }
}
