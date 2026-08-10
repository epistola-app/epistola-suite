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

    private fun htmxForm() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_FORM_URLENCODED
        add("HX-Request", "true")
    }

    /** A plain browser form POST — no HX-Request, i.e. the no-JS fallback path. */
    private fun plainForm() = HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }

    private fun payload(message: String, severity: String, enabled: Boolean, action: String = "save") = LinkedMultiValueMap<String, String>().apply {
        add("action", action)
        add("message", message)
        add("severity", severity)
        if (enabled) add("enabled", "on")
    }

    private fun save(message: String, severity: String, enabled: Boolean, action: String = "save") {
        val response = restTemplate.postForEntity(
            "/platform/banner",
            HttpEntity(payload(message, severity, enabled, action), plainForm()),
            String::class.java,
        )
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
    fun `htmx save re-renders the banner form with an OOB success notice`() {
        try {
            val response = restTemplate.postForEntity(
                "/platform/banner",
                HttpEntity(payload("Maintenance window tonight", "WARNING", enabled = true), htmxForm()),
                String::class.java,
            )

            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            // Primary swap: the form fragment (showing the saved state incl. the
            // now-visible Clear button), not a full page.
            assertThat(body).contains("Maintenance window tonight")
            assertThat(body).contains("Clear banner")
            assertThat(body).doesNotContain("<title")
            // The success notice rides the same response as an OOB swap into #notices.
            assertThat(body).contains("hx-swap-oob=\"afterbegin:#notices\"")
            assertThat(body).contains("Site banner saved.")
        } finally {
            save("", "INFO", enabled = false, action = "clear")
        }
    }

    @Test
    fun `htmx clear re-renders the form without the clear button and confirms it`() {
        save("To be cleared", "INFO", enabled = true)

        val response = restTemplate.postForEntity(
            "/platform/banner",
            HttpEntity(payload("", "INFO", enabled = false, action = "clear"), htmxForm()),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body).doesNotContain("Clear banner")
        assertThat(body).contains("Site banner cleared.")
    }

    @Test
    fun `a disabled banner is not rendered`() {
        val tenant = createTenant("Banner Hidden")

        save("Hidden message", "INFO", enabled = false)

        val page = restTemplate.getForEntity("/tenants/${tenant.id.value}/features", String::class.java)
        assertThat(page.body!!).doesNotContain("data-site-banner")
    }
}
