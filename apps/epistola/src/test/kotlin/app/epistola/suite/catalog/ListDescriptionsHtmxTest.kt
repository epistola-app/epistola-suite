// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate

/**
 * The Themes and Stencils lists and the catalog's resources table show a description under its name,
 * muted and cut to one line, rather than in a column of its own.
 */
class ListDescriptionsHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `the theme and stencil lists show each description under its name, not in a column`() {
        lateinit var tenant: Tenant
        fixture {
            given {
                tenant = tenant("Descriptions")
                withMediator {
                    CreateCatalog(tenantKey = tenant.id, id = CatalogKey.of("letters"), name = "Letters").execute()
                    val letters = testCatalog(TenantId(tenant.id), CatalogKey.of("letters"))
                    CreateTheme(ThemeId(ThemeKey.of("brand"), letters), "Brand", description = "The house colours", catalogVersion = null).execute()
                    CreateStencil(StencilId(StencilKey.of("footer"), letters), "Footer", description = "What every page ends with", catalogVersion = null).execute()
                }
            }
        }

        val themes = page(tenant, "letters", "themes")
        assertThat(themes).doesNotContain("<th>Description</th>")
        assertThat(themes).contains("data-testid=\"theme-description\"").contains("The house colours")
        assertThat(themes.indexOf("theme-description")).`as`("under the theme it describes").isGreaterThan(themes.indexOf(">Brand</a>"))

        val stencils = page(tenant, "letters", "stencils")
        assertThat(stencils).doesNotContain("<th>Description</th>")
        assertThat(stencils).contains("data-testid=\"stencil-description\"").contains("What every page ends with")
        assertThat(stencils.indexOf("stencil-description")).`as`("under the stencil it describes").isGreaterThan(stencils.indexOf(">Footer</a>"))
    }

    @Test
    fun `the resources table shows each description under its name`() {
        lateinit var tenant: Tenant
        fixture {
            given {
                tenant = tenant("Supplied Descriptions")
                withMediator {
                    RegisterCatalog(tenantKey = tenant.id, sourceUrl = "classpath:epistola/catalogs/fixture/catalog.json", authType = AuthType.NONE).execute()
                    InstallFromCatalog(catalog = testCatalog(tenant.id, CatalogKey.of("epistola-demo"))).execute()
                }
            }
        }

        val browse = page(tenant, "epistola-demo", "browse")
        assertThat(browse).doesNotContain("<th>Description</th>")
        val row = row(browse, "A clean corporate theme with professional styling.")
        val description = row.indexOf("data-testid=\"catalog-resource-description\"")
        assertThat(description).`as`("the description is in the resource's own row").isPositive()
        assertThat(row.indexOf("Corporate Theme")).`as`("under the name it describes").isBetween(0, description)
        assertThat(description).`as`("in the name's cell, before the slug's").isLessThan(row.indexOf("<code>"))
    }

    @Test
    fun `a description reading No or Off is shown, not taken for false`() {
        lateinit var tenant: Tenant
        fixture {
            given {
                tenant = tenant("Falsy Descriptions")
                withMediator {
                    CreateCatalog(tenantKey = tenant.id, id = CatalogKey.of("letters"), name = "Letters").execute()
                    val letters = testCatalog(TenantId(tenant.id), CatalogKey.of("letters"))
                    CreateTheme(ThemeId(ThemeKey.of("brand"), letters), "Brand", description = "No", catalogVersion = null).execute()
                    CreateStencil(StencilId(StencilKey.of("footer"), letters), "Footer", description = "Off", catalogVersion = null).execute()
                    // The resources table is drawn only for a catalog with a source: this one has one theme, described as "No".
                    RegisterCatalog(tenantKey = tenant.id, sourceUrl = "classpath:epistola/catalogs/falsy-description/catalog.json", authType = AuthType.NONE).execute()
                }
            }
        }

        assertThat(row(page(tenant, "letters", "themes"), ">Brand</a>")).contains("data-testid=\"theme-description\"").contains(">No<")
        assertThat(row(page(tenant, "letters", "stencils"), ">Footer</a>")).contains("data-testid=\"stencil-description\"").contains(">Off<")
        assertThat(row(page(tenant, "falsy-description", "browse"), "Plain Theme")).`as`("the resources table")
            .contains("data-testid=\"catalog-resource-description\"").contains(">No<")
    }

    /** The table row holding [text]. */
    private fun row(page: String, text: String): String {
        val at = page.indexOf(text)
        assertThat(at).`as`("'%s' is on the page", text).isNotNegative()
        return page.substring(page.lastIndexOf("<tr", at), page.indexOf("</tr>", at))
    }

    private fun page(tenant: Tenant, catalog: String, path: String): String = restTemplate
        .getForEntity("/tenants/${tenant.id.value}/catalogs/$catalog/$path", String::class.java)
        .body!!
}
