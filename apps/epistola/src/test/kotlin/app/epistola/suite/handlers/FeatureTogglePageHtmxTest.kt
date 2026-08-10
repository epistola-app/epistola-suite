// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

/**
 * Verifies the admin Features page: feature display metadata (human title, maturity badge) and
 * the save flow — an HTMX submit re-renders the toggles form with an OOB success notice, a plain
 * submit falls back to a PRG redirect.
 */
class FeatureTogglePageHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun htmxForm() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_FORM_URLENCODED
        add("HX-Request", "true")
    }

    /** A plain browser form POST — no HX-Request, i.e. the no-JS fallback path. */
    private fun plainForm() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_FORM_URLENCODED
    }

    @Test
    fun `features page shows titles and maturity badges`() {
        val tenant = createTenant("Features Page")

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}/features", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        // The Backups row renders its title and the Beta maturity badge.
        assertThat(body).contains("Backups")
        assertThat(body).contains("badge badge-beta")
        assertThat(body).contains(">Beta<")
        // The toggle checkbox still posts under the raw feature key.
        assertThat(body).contains("name=\"support-backups\"")
        assertThat(body).contains("AI Chat")
        assertThat(body).contains("badge badge-alpha")
        assertThat(body).contains(">Alpha<")
        assertThat(body).contains("name=\"ai-chat\"")
    }

    @Test
    fun `htmx save re-renders the toggles form with an OOB success notice`() {
        val tenant = createTenant("Features Save Htmx")
        val payload = LinkedMultiValueMap<String, String>().apply { add("support-backups", "on") }

        val response = restTemplate.postForEntity(
            "/tenants/${tenant.id.value}/features",
            HttpEntity(payload, htmxForm()),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        // Primary swap: the form fragment, not a full page.
        assertThat(body).contains("name=\"support-backups\"")
        assertThat(body).doesNotContain("<title")
        // The success notice rides the same response as an OOB swap into #notices.
        assertThat(body).contains("hx-swap-oob=\"afterbegin:#notices\"")
        assertThat(body).contains("Feature toggle settings saved.")
    }

    @Test
    fun `plain save falls back to a PRG redirect`() {
        val tenant = createTenant("Features Save Plain")

        // The test client follows the 303 (POST becomes GET), so the response is
        // the features page again — no notice on the plain path.
        val response = restTemplate.postForEntity(
            "/tenants/${tenant.id.value}/features",
            HttpEntity(LinkedMultiValueMap<String, String>(), plainForm()),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!).contains("Feature Toggles")
        assertThat(response.body!!).doesNotContain("Feature toggle settings saved.")
    }
}
