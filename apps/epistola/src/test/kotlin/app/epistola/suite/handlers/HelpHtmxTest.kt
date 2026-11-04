// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.testing.TEST_TENANT_MEMBERSHIPS_HEADER
import app.epistola.suite.testing.TEST_TENANT_ROLES_HEADER
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

/**
 * Help: what Epistola is for and where things are, for every member of the tenant, reached from the
 * bar on every tenant page. The places it names are links only for those who may open them.
 */
class HelpHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun get(url: String, header: Pair<String, String>? = null): ResponseEntity<String> = restTemplate.exchange(
        url,
        HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders().apply { if (header != null) set(header.first, header.second) }),
        String::class.java,
    )

    /** The links the page names, by the place they lead to. */
    private fun placeLinks(body: String): List<String> = Regex("""<a href="([^"]+)" class="help-place"""").findAll(body).map { it.groupValues[1] }.toList()

    /** The places the page names without a link, by their label. */
    private fun placesWithoutLink(body: String): List<String> = Regex("""<strong class="help-place-off"[^>]*>([^<]+)<""").findAll(body).map { it.groupValues[1] }.toList()

    /** The opening tag of the link with [testId]. */
    private fun link(body: String, testId: String): String? = Regex("""<a\b[^>]*data-testid="$testId"[^>]*>""").find(body)?.value

    @Test
    fun `a member of the tenant reads every section, with the places it names as links, and the bar calls the page Help`() {
        val tenant = createTenant("Help Member")
        val t = tenant.id.value

        val response = get("/tenants/$t/help", TEST_TENANT_MEMBERSHIPS_HEADER to t)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        listOf("help-basics", "help-words", "help-finding", "help-tasks", "help-first", "help-tips", "help-more").forEach {
            assertThat(body).contains("data-testid=\"$it\"")
        }
        assertThat(placeLinks(body)).contains(
            "/tenants/$t",
            "/tenants/$t/catalogs",
            "/tenants/$t/environments",
            "/tenants/$t/generation-history",
            "/tenants/$t/api-keys",
        )
        assertThat(placesWithoutLink(body)).isEmpty()
        assertThat(body.substringAfter("data-testid=\"ws-crumb-section\"").substringBefore("</span>")).endsWith(">Help")
        assertThat(link(body, "ws-help")).contains("aria-current=\"page\"")
    }

    @Test
    fun `a reader of content sees API keys and Generation named without a link, since the role does not include them`() {
        val tenant = createTenant("Help Reader")
        val t = tenant.id.value

        val body = get("/tenants/$t/help", TEST_TENANT_ROLES_HEADER to "CONTENT_VIEWER").body!!

        assertThat(placesWithoutLink(body)).contains("API key", "Generation").doesNotContain("All catalogs", "Environments")
        assertThat(placeLinks(body)).contains("/tenants/$t/catalogs", "/tenants/$t/environments")
            .doesNotContain("/tenants/$t/api-keys", "/tenants/$t/generation-history")
    }

    @Test
    fun `someone who is not a member of the tenant is refused`() {
        val tenant = createTenant("Help Outsider")
        val other = createTenant("Help Other")

        val response = get("/tenants/${tenant.id.value}/help", TEST_TENANT_MEMBERSHIPS_HEADER to other.id.value)

        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `every tenant page leads to Help from the bar, and the home page from getting started`() {
        val tenant = createTenant("Help Bar")
        val t = tenant.id.value

        val environments = get("/tenants/$t/environments", TEST_TENANT_MEMBERSHIPS_HEADER to t).body!!
        assertThat(link(environments, "ws-help")).contains("href=\"/tenants/$t/help\"").doesNotContain("aria-current")

        val home = get("/tenants/$t", TEST_TENANT_MEMBERSHIPS_HEADER to t).body!!
        assertThat(link(home, "getting-started-help")).contains("href=\"/tenants/$t/help\"")
    }
}
