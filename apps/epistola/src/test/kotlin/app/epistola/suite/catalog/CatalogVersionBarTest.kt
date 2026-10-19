// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CreateCatalogDraft
import app.epistola.suite.catalog.versions.DraftRef
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployCatalogVersion
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.queries.ListThemes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

/**
 * Every catalog content page says, in the context bar, what is being changed (docs/workspace-shell.md):
 * the catalog and the next version, with what is not published yet and Publish; side work, named by
 * what it fixes and where that is live, with a strip saying the next version is not affected; or a
 * subscribed catalog that cannot be edited.
 */
class CatalogVersionBarTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val catalogKey = CatalogKey.of("bar-cat")

    private fun seed(name: String): Tenant {
        lateinit var tenant: Tenant
        fixture {
            given {
                tenant = tenant(name)
                withMediator {
                    val catalog = CatalogId(catalogKey, TenantId(tenant.id))
                    CreateCatalog(tenantKey = tenant.id, id = catalogKey, name = "Bar cat").execute()
                    CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), catalog), "Invoice")
                        .execute().withRequiredDataExample()
                    CreateStencil(id = StencilId(StencilKey.of("address"), catalog), name = "Address").execute()
                    CreateTheme(id = ThemeId(ThemeKey.of("house"), catalog), name = "House").execute()
                    CreateCodeList(
                        id = CodeListId(CodeListKey.of("locales"), catalog),
                        displayName = "Locales",
                        sourceType = CodeListSource.INLINE,
                        entries = listOf(CodeListEntry("en", "English")),
                    ).execute()
                    mediator.releaseNext(catalog) // 1.0.0; the draft is now 1.0.1
                }
            }
        }
        return tenant
    }

    @Test
    fun `every content page names its catalog and the next version, and loads what is not published yet with Publish`() {
        val tenant = seed("Version Bar Pages")
        withMediator { CreateTheme(id = ThemeId(ThemeKey.of("extra"), CatalogId(catalogKey, TenantId(tenant.id))), name = "Extra").execute() }

        listOf(
            "/catalogs/bar-cat/templates/invoice",
            "/catalogs/bar-cat/stencils/address",
            "/catalogs/bar-cat/themes/house",
            "/catalogs/bar-cat/code-lists/locales",
        ).forEach { path ->
            val body = get(tenant, path)
            assertThat(body.substringAfter("data-testid=\"ws-crumb-catalog\"").substringBefore("</button>"))
                .`as`("$path names the catalog in the bar").contains(">Bar cat<")
            assertThat(body.substringAfter("data-testid=\"ws-crumb-version\"").substringBefore("</button>"))
                .`as`("$path says it is the next version, without a number").contains(">Next version<").doesNotContain("v1.0.1")
            assertThat(body).`as`("$path loads the bar's status").contains("hx-get=\"/tenants/${tenant.id.value}/catalogs/bar-cat/bar-status\"")
            assertThat(body).`as`("$path mounts the publish dialog").contains("id=\"release-dialog-container\"")
            assertThat(body).`as`("$path shows the catalog's nav").contains("data-testid=\"ws-nav-templates\"").contains("data-testid=\"ws-nav-versions\"")
        }

        val status = barStatus(tenant, "bar-cat")
        assertThat(status).contains("1 change not published").contains("data-testid=\"catalog-bar-publish\"")
            .contains("/catalogs/bar-cat/release")
    }

    @Test
    fun `parallel work is named, with a strip and the way back, and is published on its own`() {
        val tenant = seed("Version Bar Parallel")
        val work = withMediator {
            val draft = CreateCatalogDraft(tenant.id, catalogKey, "Redesign 2027").execute()
            // The work changes something, so it has changes of its own.
            CreateTheme(
                id = ThemeId(ThemeKey.of("fixed"), CatalogId(catalogKey, TenantId(tenant.id))),
                name = "Fixed",
                catalogVersion = CatalogVersionRef(tenant.id, catalogKey, DraftRef.Id(draft.versionId)),
            ).execute()
            draft.versionId
        }

        val page = get(tenant, "/catalogs/bar-cat@$work/themes/house")
        assertThat(page).contains("ws-bar-side")
        assertThat(page.substringAfter("data-testid=\"ws-crumb-version\"").substringBefore("</button>")).contains(">Redesign 2027<")
        assertThat(page).contains("data-testid=\"catalog-parallel-banner\"").contains("You are changing Redesign 2027, parallel work kept apart from the next version")
        assertThat(page).contains("hx-get=\"/tenants/${tenant.id.value}/catalogs/bar-cat@$work/bar-status\"")
        assertThat(page).contains("href=\"/tenants/${tenant.id.value}/catalogs/bar-cat/themes/house\" data-testid=\"catalog-parallel-back\"")
        assertThat(barStatus(tenant, "bar-cat@$work")).`as`("any draft is published on its own").contains("data-testid=\"catalog-bar-publish\"")
            .`as`("its changes, not what is published").contains(">1 change<").doesNotContain("not published")

        val next = get(tenant, "/catalogs/bar-cat/themes/house")
        assertThat(next).doesNotContain("data-testid=\"catalog-parallel-banner\"")
        assertThat(next.substringAfter("data-testid=\"ws-version-menu\"").substringBefore("ws-menu-sep"))
            .contains(">Next version<").contains(">Redesign 2027<")
            .`as`("a switch keeps the page someone is on").contains("href=\"/tenants/${tenant.id.value}/catalogs/bar-cat@$work/switch\"")
        assertThat(Regex("ws-menu-check").findAll(next.substringAfter("data-testid=\"ws-version-menu\"").substringBefore("ws-menu-sep")).count())
            .`as`("only the version the page is in is ticked").isEqualTo(1)
        assertThat(next).`as`("parallel work is started from the bar").contains("data-testid=\"ws-start-parallel\"")
    }

    @Test
    fun `a subscribed catalog's page shows its version and that it cannot be edited, with nothing to publish`() {
        val tenant = seed("Version Bar Subscribed")
        val installed: String = withMediator { GetCatalog(tenant.id, SYSTEM_CATALOG_KEY).query()!!.installedReleaseVersion!! }
        val theme: String = withMediator { ListThemes(TenantId(tenant.id)).query().first { it.catalogKey == SYSTEM_CATALOG_KEY }.id.value }

        val body = get(tenant, "/catalogs/system/themes/$theme")
        assertThat(body.substringAfter("data-testid=\"ws-crumb-version\"").substringBefore("</button>")).contains(">v$installed<")
        assertThat(body).contains("data-testid=\"catalog-version-bar-read-only\"").contains("it can't be edited here")
        assertThat(body).doesNotContain("/bar-status").doesNotContain(">Next version<")
    }

    /** The bar's status, as the bar loads it after the page. */
    private fun barStatus(tenant: Tenant, segment: String): String = restTemplate.exchange(
        "/tenants/${tenant.id.value}/catalogs/$segment/bar-status",
        HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders().apply { set("HX-Request", "true") }),
        String::class.java,
    ).body!!

    private fun get(tenant: Tenant, path: String): String {
        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}$path", String::class.java)
        assertThat(response.statusCode).`as`(path).isEqualTo(HttpStatus.OK)
        return response.body!!
    }
}
