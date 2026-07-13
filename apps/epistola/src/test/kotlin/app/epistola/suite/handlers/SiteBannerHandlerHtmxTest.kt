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
 * Verifies the platform-manager Site Banner admin page and that a saved banner is
 * rendered into the app shell (below the nav) by [app.epistola.suite.config.SiteBannerInterceptor].
 *
 * The app test principal carries the platform `TENANT_MANAGER` role, so the mediator
 * gate on the banner commands/queries is satisfied. The banner is installation-wide,
 * so each test drives it to a known state before asserting.
 */
class SiteBannerHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun form() = HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }

    private fun save(tenantId: String, message: String, severity: String, enabled: Boolean, action: String = "save") {
        val payload = LinkedMultiValueMap<String, String>()
        payload.add("action", action)
        payload.add("message", message)
        payload.add("severity", severity)
        if (enabled) payload.add("enabled", "on")
        val response = restTemplate.postForEntity(
            "/tenants/$tenantId/site-banner",
            HttpEntity(payload, form()),
            String::class.java,
        )
        // 303 redirect (whether or not the client follows it) — never an error.
        assertThat(response.statusCode.value()).isLessThan(400)
    }

    @Test
    fun `edit page renders the form`() {
        val tenant = createTenant("Banner Form")

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}/site-banner", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body).contains("Site Banner")
        assertThat(body).contains("name=\"message\"")
        assertThat(body).contains("name=\"severity\"")
    }

    @Test
    fun `saving an enabled banner renders the strip in the shell`() {
        val tenant = createTenant("Banner Show")

        save(tenant.id.value, "Scheduled maintenance tonight", "WARNING", enabled = true)

        val page = restTemplate.getForEntity("/tenants/${tenant.id.value}/site-banner", String::class.java)
        val body = page.body!!
        assertThat(body).contains("data-site-banner")
        assertThat(body).contains("alert-warning")
        assertThat(body).contains("Scheduled maintenance tonight")
    }

    @Test
    fun `a disabled banner is not rendered`() {
        val tenant = createTenant("Banner Hidden")

        save(tenant.id.value, "Hidden message", "INFO", enabled = false)

        val page = restTemplate.getForEntity("/tenants/${tenant.id.value}/site-banner", String::class.java)
        assertThat(page.body!!).doesNotContain("data-site-banner")
    }
}
