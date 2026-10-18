// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity

/**
 * A boosted navigation inside the workspace replaces only the page (docs/workspace-shell.md): the
 * side nav comes along out of band, the context bar only when it changed, and otherwise its status
 * refreshes in place. From a page without the workspace the whole body is swapped, as before.
 */
class WorkspaceBoostHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun seed(name: String): Tenant = createTenant(name).also {
        withMediator { CreateCatalog(it.id, CatalogKey.of("letters"), "Letters").execute() }
    }

    private fun shellKey(page: String): String = Regex("""id="ws-shell-key" hidden data-key="([^"]+)"""").find(page)!!.groupValues[1]

    private fun boosted(tenant: Tenant, path: String, shell: String?): ResponseEntity<String> = restTemplate.exchange(
        "/tenants/${tenant.id.value}$path",
        HttpMethod.GET,
        HttpEntity<Void>(
            HttpHeaders().apply {
                set("HX-Request", "true")
                set("HX-Boosted", "true")
                shell?.let { set("X-Epistola-Shell", it) }
            },
        ),
        String::class.java,
    )

    @Test
    fun `within one catalog version only the page and the nav are sent, and the bar's status refreshes in place`() {
        val tenant = seed("Boost Same Bar")
        val from = restTemplate.getForEntity("/tenants/${tenant.id.value}/catalogs/letters/browse", String::class.java).body!!

        val response = boosted(tenant, "/stencils?catalog=letters", shellKey(from))

        assertThat(response.headers.getFirst("HX-Retarget")).isEqualTo("#ws-page")
        assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("outerHTML")
        assertThat(response.headers.getFirst("HX-Trigger")).isEqualTo("ws-status-refresh")
        val body = response.body!!
        assertThat(body).contains("id=\"ws-page\"").contains("<title>")
        assertThat(body).contains("id=\"ws-nav\"").contains("hx-swap-oob=\"true\"")
        assertThat(body).`as`("the bar did not change").doesNotContain("id=\"app-nav\"")
        assertThat(body).`as`("not the whole document").doesNotContain("class=\"app-shell\"")
    }

    @Test
    fun `when the bar changes it comes along out of band`() {
        val tenant = seed("Boost Bar Changes")
        val from = restTemplate.getForEntity("/tenants/${tenant.id.value}/catalogs/letters/browse", String::class.java).body!!

        val body = boosted(tenant, "/environments", shellKey(from)).body!!

        assertThat(body.substringAfter("id=\"app-nav\"").substringBefore(">")).contains("hx-swap-oob=\"true\"")
        assertThat(body).contains("data-testid=\"ws-crumb-section\">Environments<")
    }

    @Test
    fun `from a page without the workspace the whole page is sent`() {
        val tenant = seed("Boost Plain")

        listOf(null, "plain").forEach { shell ->
            val response = boosted(tenant, "/catalogs/letters/browse", shell)
            assertThat(response.headers.getFirst("HX-Retarget")).`as`("$shell").isNull()
            assertThat(response.body!!).contains("class=\"app-shell\"").contains("id=\"app-nav\"")
        }
    }
}
