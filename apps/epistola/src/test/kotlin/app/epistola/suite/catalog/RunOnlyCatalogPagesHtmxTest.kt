// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource

/**
 * An installation that only runs releases (ADR 0032, section 3): no catalog page offers to change or
 * publish anything, and each says why.
 */
@TestPropertySource(properties = ["epistola.catalog.editing.enabled=false"])
class RunOnlyCatalogPagesHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun get(tenant: Tenant, path: String): String {
        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}$path", String::class.java)
        assertThat(response.statusCode).`as`(path).isEqualTo(HttpStatus.OK)
        return response.body!!
    }

    @Test
    fun `a catalog page offers no edit or publish controls and says the installation only runs releases`() {
        lateinit var tenant: Tenant
        fixture { given { tenant = tenant("Run Only Pages") } }

        val browse = get(tenant, "/catalogs/default/browse")
        assertThat(browse.substringAfter("data-testid=\"catalog-read-only-reason\"").substringBefore("</span>"))
            .contains(">This installation only runs releases").contains("only receives and runs releases")
        assertThat(browse).`as`("no next version to publish").doesNotContain("/catalogs/default/bar-status")
        assertThat(browse).doesNotContain("data-testid=\"ws-start-parallel\"").doesNotContain("Change in Settings")

        val themes = get(tenant, "/catalogs/default/themes")
        assertThat(themes.substringAfter("data-testid=\"catalog-version-bar-read-only\"").substringBefore("</span>"))
            .contains(">This installation only runs releases")
        assertThat(themes).doesNotContain("New Theme")

        val list = get(tenant, "/catalogs")
        assertThat(list.substringAfter("id=\"catalog-list\"").substringAfter("catalogs/default/browse").substringBefore("</tr>"))
            .contains(">This installation only runs releases<").doesNotContain("Publish a new release")
    }
}
