// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
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
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus
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

    private fun get(path: String): String {
        val response = restTemplate.getForEntity(path, String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return response.body!!
    }

    private fun templatePage(tenant: TenantKey, tab: String) = get("/tenants/${tenant.value}/catalogs/${testCatalog(tenant, letters).address}/templates/invoice$tab")

    private fun fonts(tenant: TenantKey, catalog: CatalogKey = letters) = objectMapper.readTree(get("/tenants/${tenant.value}/fonts/search?catalog=${testCatalog(tenant, catalog).address}")).values()

    @Test
    fun `the template theme picker offers the system catalog's themes and a declared catalog's, not an undeclared one's`() {
        val tenant = setUp("theme-picker")

        val before = templatePage(tenant, "/settings")
        assertThat(before).contains("value=\"${systemCatalog(tenant).address}/default\"")
        assertThat(before).doesNotContain("Shared brand")

        declare(tenant, plus = true)

        assertThat(templatePage(tenant, "/settings")).contains("value=\"${testCatalog(tenant, shared).address}/brand\"")
    }

    @Test
    fun `the variant attribute picker offers the system locale and a declared catalog's attributes, not an undeclared one's`() {
        val tenant = setUp("attribute-picker")

        val before = templatePage(tenant, "")
        assertThat(before).contains("attr_system.locale")
        assertThat(before).doesNotContain("attr_shared.audience")

        declare(tenant, plus = true)

        assertThat(templatePage(tenant, "")).contains("attr_shared.audience")
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
    fun `a catalog that does not declare the system catalog is offered none of its fonts`() {
        val tenant = setUp("font-undeclared")

        declare(tenant, plus = false, system = false)

        assertThat(fonts(tenant).map { it["catalogKey"].asString() }).doesNotContain("system")
    }

    @Test
    fun `the catalog default theme picker offers the system catalog's themes and a declared catalog's`() {
        val tenant = setUp("default-theme-picker")
        declare(tenant, plus = true)

        val page = get("/tenants/${tenant.value}/catalogs/${testCatalog(tenant, letters).address}/theme-settings")

        assertThat(page).contains("Shared brand (shared)")
        assertThat(page).contains("(system)")
    }
}
