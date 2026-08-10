// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

private const val DEMO_CATALOG_URL = "classpath:epistola/catalogs/demo/catalog.json"

/**
 * Handler-level coverage for the browse-page install endpoint: a successful
 * install re-renders the resource rows with an OOB success notice (200 → the
 * preview dialog closes), and a failed install carries a real error status
 * with an error notice while STILL refreshing the rows (partial installs did
 * land) — the 422 keeps the preview dialog open via close-on-success.
 */
class CatalogInstallHandlerTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun htmxForm() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_FORM_URLENCODED
        add("HX-Request", "true")
    }

    private fun subscribedTenant(name: String): Tenant = fixtureTenant(name).also { t ->
        withMediator {
            RegisterCatalog(tenantKey = t.id, sourceUrl = DEMO_CATALOG_URL, authType = AuthType.NONE).execute()
        }
    }

    private fun fixtureTenant(name: String): Tenant = createTenant(name)

    @Test
    fun `htmx install success returns the rows with an OOB success notice`() {
        val tenant = subscribedTenant("Install Success Tenant")

        val response = restTemplate.postForEntity(
            "/tenants/${tenant.id.value}/catalogs/epistola-demo/install",
            HttpEntity(LinkedMultiValueMap<String, String>(), htmxForm()),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body).contains("id=\"resource-table\"")
        assertThat(body).contains("hx-swap-oob=\"afterbegin:#notices\"")
        assertThat(body).contains("installed")
        assertThat(response.headers.getFirst("HX-Trigger")).contains("installComplete")
    }

    @Test
    fun `htmx install failure carries 422 with an error notice and still refreshes the rows`() {
        val tenant = fixtureTenant("Install Failure Tenant")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = CatalogKey.of("authored-cat"), name = "Authored").execute()
        }

        // Installing from an AUTHORED catalog is rejected by the command — the
        // exception path must answer 422 + error notice, not a silent 200.
        val response = restTemplate.postForEntity(
            "/tenants/${tenant.id.value}/catalogs/authored-cat/install",
            HttpEntity(LinkedMultiValueMap<String, String>(), htmxForm()),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("outerHTML")
        val body = response.body!!
        assertThat(body).contains("Failed to install")
        assertThat(body).contains("id=\"resource-table\"")
    }
}
