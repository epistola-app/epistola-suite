package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus

/**
 * Verifies the module-contributed navigation renders into the app shell: the host's core groups
 * are always present, the Support group appears only when a support feature is toggled on, and the
 * active section is highlighted from the request path. Permission-based item visibility is covered
 * by the NavMenuAggregator unit test; here the default test user is a manager.
 */
class NavRenderHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `core nav groups render on the tenant shell`() {
        val tenant = createTenant("Nav Core")

        val body = restTemplate.getForEntity("/tenants/${tenant.id.value}/templates", String::class.java).let {
            assertThat(it.statusCode).isEqualTo(HttpStatus.OK)
            it.body!!
        }

        assertThat(body).contains("nav-dropdown-authoring", "nav-dropdown-resources", "nav-dropdown-operations", "nav-dropdown-settings")
        assertThat(body).contains("/tenants/${tenant.id.value}/themes", "/tenants/${tenant.id.value}/code-lists")
        // Active highlighting: the Templates item is marked active on the templates page.
        // (Thymeleaf classappend can emit extra whitespace, so normalise before matching.)
        assertThat(body).contains("nav-item-templates")
        assertThat(body.replace(Regex("\\s+"), " ")).contains("app-nav-dropdown-item active")
    }

    @Test
    fun `support group is hidden when no support feature is enabled`() {
        val tenant = createTenant("Nav No Support")
        // feedback defaults on (freely usable), so disable every support feature to
        // leave the Support group empty.
        withMediator {
            SaveFeatureToggle(tenant.id, KnownFeatures.SUPPORT_FEEDBACK, enabled = false).execute()
            SaveFeatureToggle(tenant.id, KnownFeatures.SUPPORT_BACKUPS, enabled = false).execute()
            SaveFeatureToggle(tenant.id, KnownFeatures.SUPPORT_COMPATIBILITY_CHECK, enabled = false).execute()
        }

        val body = restTemplate.getForEntity("/tenants/${tenant.id.value}/templates", String::class.java).body!!

        assertThat(body).doesNotContain("nav-dropdown-support")
        // The feedback footer FAB is also contributed via the footer SPI, so it disappears too.
        assertThat(body).doesNotContain("feedback-capture.js")
    }

    @Test
    fun `support group shows the enabled feature plus overview`() {
        val tenant = createTenant("Nav Support On")
        withMediator {
            SaveFeatureToggle(tenant.id, KnownFeatures.SUPPORT_FEEDBACK, enabled = true).execute()
        }

        val body = restTemplate.getForEntity("/tenants/${tenant.id.value}/templates", String::class.java).body!!

        assertThat(body).contains("nav-dropdown-support")
        assertThat(body).contains("/tenants/${tenant.id.value}/support") // Overview
        assertThat(body).contains("/tenants/${tenant.id.value}/feedback")
        // Backups/Upgrading toggles are off, so their items are absent.
        assertThat(body).doesNotContain("/tenants/${tenant.id.value}/backups")
        assertThat(body).doesNotContain("/tenants/${tenant.id.value}/upgrading")
        // The feedback module also contributes the footer FAB via the footer SPI.
        assertThat(body).contains("feedback-capture.js")
    }
}
