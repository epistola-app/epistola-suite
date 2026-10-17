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
import app.epistola.suite.catalog.versions.CreateCatalogVersion
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
import app.epistola.suite.environments.commands.DeployRelease
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
import org.springframework.http.HttpStatus

/**
 * Every catalog content page says, above its header, what is being changed
 * (docs/catalog-workspace-navigation.md): your work in a catalog, with Publish; a fix of a published
 * version, as a banner naming it and where it is live; or a subscribed catalog that cannot be edited.
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
    fun `every content page names its catalog and says it is the next version, with Publish`() {
        val tenant = seed("Version Bar Pages")

        listOf(
            "/templates/bar-cat/invoice",
            "/stencils/bar-cat/address",
            "/themes/bar-cat/house",
            "/code-lists/bar-cat/locales",
        ).forEach { path ->
            val body = get(tenant, path)
            assertThat(body).`as`("$path carries the bar").contains("data-testid=\"catalog-version-bar\"")
            assertThat(body.indexOf("data-testid=\"catalog-version-bar\""))
                .`as`("$path puts the bar above the page header")
                .isLessThan(body.indexOf("class=\"page-header\""))
            assertThat(body).`as`("$path names the catalog").contains(">Bar cat</a>")
            assertThat(body).`as`("$path says it is the next version").contains(">Next version</a>")
            assertThat(body).`as`("$path offers publishing").contains("data-testid=\"catalog-bar-publish\"")
            assertThat(body).`as`("$path publishes the next version").contains("/catalogs/bar-cat/release")
            assertThat(body).`as`("$path mounts the publish dialog").contains("id=\"release-dialog-container\"")
            assertThat(body).`as`("$path shows no number for the next version").doesNotContain("Draft v")
        }
    }

    @Test
    fun `another draft is a banner that says it is not the next version, and where its base is live`() {
        val tenant = seed("Version Bar Fix")
        withMediator {
            CreateCatalogVersion(tenant.id, catalogKey, "2.0.0").execute() // the next version; 1.0.1 is now another draft
            CreateEnvironment(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant.id)), "Production").execute()
            DeployRelease(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant.id)), catalogKey, "1.0.0", includeDependencies = true).execute()
        }

        val fix = get(tenant, "/themes/bar-cat@1.0.1/house")
        assertThat(fix).contains("data-testid=\"catalog-fix-banner\"").doesNotContain("data-testid=\"catalog-version-bar\"")
        assertThat(fix).contains("You are editing draft v1.0.1 of Bar cat, not the next version")
        assertThat(fix).contains("It started from v1.0.0, which is live in production")
        assertThat(fix).contains("Publish…").contains("/catalogs/bar-cat@1.0.1/release")
        assertThat(fix).contains("href=\"/tenants/${tenant.id.value}/themes/bar-cat/house\"")

        val work = get(tenant, "/themes/bar-cat/house")
        assertThat(work).contains(">Next version</a>").contains(">Draft v1.0.1</a>")
            .contains("href=\"/tenants/${tenant.id.value}/themes/bar-cat@1.0.1/house\"")
    }

    @Test
    fun `a subscribed catalog's page shows its version and that it cannot be edited, with nothing to publish`() {
        val tenant = seed("Version Bar Subscribed")
        val installed: String = withMediator { GetCatalog(tenant.id, SYSTEM_CATALOG_KEY).query()!!.installedReleaseVersion!! }
        val theme: String = withMediator { ListThemes(TenantId(tenant.id)).query().first { it.catalogKey == SYSTEM_CATALOG_KEY }.id.value }

        val body = get(tenant, "/themes/system/$theme")
        assertThat(body).contains("data-testid=\"catalog-version-bar\"")
        assertThat(body).contains(">v$installed<")
        assertThat(body).contains("can't be edited")
        assertThat(body).doesNotContain("data-testid=\"catalog-bar-publish\"").doesNotContain(">Next version</a>")
    }

    private fun get(tenant: Tenant, path: String): String {
        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}$path", String::class.java)
        assertThat(response.statusCode).`as`(path).isEqualTo(HttpStatus.OK)
        return response.body!!
    }
}
