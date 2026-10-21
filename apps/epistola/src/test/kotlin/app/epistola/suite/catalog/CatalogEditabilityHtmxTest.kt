// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus

/**
 * The web UI decides by whether a catalog can be changed here, not by where its releases come from
 * (ADR 0032): a catalog received from a file offers its edit controls and its next version like one
 * made here, and a catalog Epistola supplies offers none and says why.
 */
class CatalogEditabilityHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val received = CatalogKey.of("received-ui")

    /** A catalog released as 1.0.0 in another tenant, received from its archive. */
    private fun receiver(name: String): Tenant {
        lateinit var tenant: Tenant
        fixture {
            given {
                val author = tenant("$name Author")
                tenant = tenant(name)
                withMediator {
                    CreateCatalog(tenantKey = author.id, id = received, name = "Received").execute()
                    CreateTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId(received, TenantId(author.id))), name = "Brand").execute()
                    MarkCatalogReady(author.id, received).execute()
                    ReleaseCatalogVersion(tenantKey = author.id, catalogKey = received, version = "1.0.0").execute()
                    val archive = ExportCatalogZip(author.id, received, "1.0.0").execute().zipBytes
                    ImportCatalogZip(tenant.id, archive).execute()
                }
            }
        }
        return tenant
    }

    private fun get(tenant: Tenant, path: String): String {
        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}$path", String::class.java)
        assertThat(response.statusCode).`as`(path).isEqualTo(HttpStatus.OK)
        return response.body!!
    }

    @Test
    fun `a received catalog that can be changed here offers its edit controls and its next version`() {
        val tenant = receiver("Editable Received")

        val themes = get(tenant, "/catalogs/received-ui/themes")
        assertThat(themes).doesNotContain("data-testid=\"catalog-version-bar-read-only\"")
        assertThat(themes).`as`("something to create in it").contains("New Theme")
        assertThat(themes).`as`("its rows are not marked read-only").doesNotContain(">Read-only</span>")
        assertThat(themes.substringAfter("data-testid=\"ws-crumb-version\"").substringBefore("</button>")).contains(">Next release<")
        assertThat(themes).`as`("what is not published yet, and Publish").contains("/catalogs/received-ui/bar-status")
        assertThat(themes.substringAfter("data-testid=\"ws-catalog-menu\"").substringBefore("Read-only here"))
            .`as`("in the switcher, among the catalogs that can be changed").contains("You can change").contains("data-catalog=\"received-ui\"")

        val browse = get(tenant, "/catalogs/received-ui/browse")
        assertThat(browse).doesNotContain("data-testid=\"catalog-read-only-reason\"").contains("Change in Settings")

        val list = get(tenant, "/catalogs")
        val row = list.substringAfter("id=\"catalog-list\"").substringAfter("catalogs/received-ui/browse").substringBefore("</tr>")
        assertThat(row).contains("data-testid=\"catalog-list-editable\"").contains("Publish a new release")
    }

    @Test
    fun `the system catalog is read-only, says Epistola supplies it, and offers nothing that changes it`() {
        val tenant = receiver("Supplied System")

        val browse = get(tenant, "/catalogs/system/browse")
        val reason = browse.substringAfter("data-testid=\"catalog-read-only-reason\"").substringBefore("</span>")
        assertThat(reason).contains(">Supplied by Epistola").contains("replaces it on every upgrade")
        assertThat(browse).contains("data-testid=\"catalog-shown-release\"")
        assertThat(browse).doesNotContain("/catalogs/system/bar-status").doesNotContain("Change in Settings")

        val themes = get(tenant, "/catalogs/system/themes")
        assertThat(themes).doesNotContain("New Theme")

        val list = get(tenant, "/catalogs")
        val row = list.substringAfter("id=\"catalog-list\"").substringAfter("catalogs/system/browse").substringBefore("</tr>")
        assertThat(row).contains("data-testid=\"catalog-list-read-only\"").contains(">Supplied by Epistola<").doesNotContain("Publish a new rersion")
        assertThat(list).doesNotContain(">Authored<").doesNotContain(">Subscribed<")
    }
}
