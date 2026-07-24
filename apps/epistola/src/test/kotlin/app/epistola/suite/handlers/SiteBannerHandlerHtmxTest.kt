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
 * Verifies the platform-level Site Banner admin page (`/platform/banner`) and that a
 * saved banner is rendered into the tenant app shell (below the nav) by
 * [app.epistola.suite.config.SiteBannerInterceptor].
 *
 * The app test principal carries the platform `TENANT_MANAGER` role, so the mediator
 * gate on the banner commands/queries is satisfied. The banner is installation-wide,
 * so each test drives it to a known state before asserting.
 */
class SiteBannerHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun form() = HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }

    private fun save(message: String, severity: String, enabled: Boolean, action: String = "save") {
        val payload = LinkedMultiValueMap<String, String>()
        payload.add("action", action)
        payload.add("message", message)
        payload.add("severity", severity)
        if (enabled) payload.add("enabled", "on")
        val response = restTemplate.postForEntity("/platform/banner", HttpEntity(payload, form()), String::class.java)
        // 303 redirect (whether or not the client follows it) — never an error.
        assertThat(response.statusCode.value()).isLessThan(400)
    }

    @Test
    fun `edit page renders the form`() {
        val response = restTemplate.getForEntity("/platform/banner", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body).contains("Site Banner")
        assertThat(body).contains("name=\"message\"")
        assertThat(body).contains("name=\"severity\"")
    }

    @Test
    fun `saving an enabled banner renders the strip in the tenant shell`() {
        val tenant = createTenant("Banner Show")

        save("Scheduled maintenance tonight", "WARNING", enabled = true)

        val page = restTemplate.getForEntity("/tenants/${tenant.id.value}/features", String::class.java)
        val body = page.body!!
        assertThat(body).contains("data-site-banner")
        assertThat(body).contains("alert-warning")
        assertThat(body).contains("Scheduled maintenance tonight")
    }

    @Test
    fun `a disabled banner is not rendered`() {
        val tenant = createTenant("Banner Hidden")

        save("Hidden message", "INFO", enabled = false)

        val page = restTemplate.getForEntity("/tenants/${tenant.id.value}/features", String::class.java)
        assertThat(page.body!!).doesNotContain("data-site-banner")
    }
}
