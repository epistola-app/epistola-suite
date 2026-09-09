// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.TestPrincipalUser
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
    fun `shared chrome renders the application display name`() {
        val tenant = createTenant("Nav Display Name")

        val body = restTemplate.getForEntity("/tenants/${tenant.id.value}/templates", String::class.java).body!!
        val navUser = body.substringAfter("""class="app-nav-username"""").substringBefore("</a>")
        val footerUser = body.substringAfter("""class="user-info"""").substringBefore("</footer>")

        assertThat(navUser).contains(">${TestPrincipalUser.DISPLAY_NAME}")
        assertThat(footerUser).contains(">${TestPrincipalUser.DISPLAY_NAME}</a>")
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
        // Feedback is a stable feature, so its nav item carries no maturity badge.
        assertThat(body).doesNotContain("badge badge-beta", "badge badge-alpha")
        // The feedback module also contributes the footer FAB via the footer SPI.
        assertThat(body).contains("feedback-capture.js")
    }

    @Test
    fun `relocation contributes an Organise nav item and owns its active section`() {
        val tenant = createTenant("Nav Organise")

        // Off by default: enabling the toggle is the only thing that reveals it.
        val without = restTemplate.getForEntity("/tenants/${tenant.id.value}/catalogs", String::class.java).body!!
        assertThat(without).doesNotContain("nav-item-catalog-organise")

        withMediator {
            SaveFeatureToggle(tenant.id, KnownFeatures.RESOURCE_RELOCATION, enabled = true).execute()
        }

        val body = restTemplate.getForEntity("/tenants/${tenant.id.value}/catalogs", String::class.java).body!!
        assertThat(body).contains("nav-item-catalog-organise")
        assertThat(body).contains("/tenants/${tenant.id.value}/catalogs/organise")

        // The organise page claims its own section rather than lighting up Catalogs: the
        // aggregator resolves by longest matching path suffix, so "catalogs/organise" must win
        // over "catalogs" here.
        val organise = restTemplate.getForEntity("/tenants/${tenant.id.value}/catalogs/organise", String::class.java).body!!
        val normalised = organise.replace(Regex("\\s+"), " ")
        // `class` is rendered before `data-testid`, so match the whole opening tag.
        val organiseAnchor = Regex("""<a[^>]*nav-item-catalog-organise[^>]*>""").find(normalised)?.value
        val catalogsAnchor = Regex("""<a[^>]*nav-item-catalogs[^>]*>""").find(normalised)?.value
        assertThat(organiseAnchor).isNotNull().contains("active")
        assertThat(catalogsAnchor).isNotNull().doesNotContain("active")
    }

    @Test
    fun `beta feature renders a maturity badge on its nav item`() {
        val tenant = createTenant("Nav Beta Badge")
        withMediator {
            SaveFeatureToggle(tenant.id, KnownFeatures.SUPPORT_BACKUPS, enabled = true).execute()
        }

        // Render a page whose header is NOT Backups, so the only Beta badge in the markup is the one
        // the nav item emits — this isolates the nav-template rendering from the feature page header.
        val body = restTemplate.getForEntity("/tenants/${tenant.id.value}/templates", String::class.java).body!!

        assertThat(body).contains("/tenants/${tenant.id.value}/backups")
        assertThat(body).contains("badge badge-beta")
    }
}
