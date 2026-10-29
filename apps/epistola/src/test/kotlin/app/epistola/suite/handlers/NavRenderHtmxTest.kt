// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogKey
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
    fun `outside a catalog the tenant nav lists the catalogs, then the groups, with the page marked`() {
        val tenant = createTenant("Nav Core")
        withMediator { CreateCatalog(tenant.id, CatalogKey.of("letters"), "Letters").execute() }

        val body = restTemplate.getForEntity("/tenants/${tenant.id.value}/environments", String::class.java).let {
            assertThat(it.statusCode).isEqualTo(HttpStatus.OK)
            it.body!!
        }

        assertThat(body).contains("nav-group-catalogs", "nav-group-documents", "nav-group-operations", "nav-group-settings")
        // The catalogs come first in their group; the system catalog, part of every tenant, is not among them.
        assertThat(body).contains("href=\"/tenants/${tenant.id.value}/catalogs/${tenant.id.value}/letters/browse\" class=\"ws-nav-item\" data-catalog=\"letters\"")
        assertThat(body).doesNotContain("data-catalog=\"system\" data-testid=\"ws-nav-catalog\"")
        // Parts live in a catalog's own nav, not here.
        assertThat(body).doesNotContain("nav-item-themes").doesNotContain("nav-item-code-lists")
        // The page is marked in the nav and named in the bar.
        assertThat(body.replace(Regex("\\s+"), " ")).contains("class=\"ws-nav-item active\" data-testid=\"nav-item-environments\" aria-current=\"page\"")
        assertThat(body).contains("data-testid=\"ws-crumb-section\">Environments<")
    }

    @Test
    fun `the tenant nav lists the five catalogs changed most recently`() {
        val tenant = createTenant("Nav Recent")
        withMediator { (1..7).forEach { CreateCatalog(tenant.id, CatalogKey.of("catalog-$it"), "Catalog $it").execute() } }

        val body = restTemplate.getForEntity("/tenants/${tenant.id.value}/environments", String::class.java).body!!

        val listed = Regex("""data-catalog="([^"]+)" data-testid="ws-nav-catalog"""").findAll(body).map { it.groupValues[1] }.toList()
        assertThat(listed).`as`("newest first").containsExactly("catalog-7", "catalog-6", "catalog-5", "catalog-4", "catalog-3")
        assertThat(body).contains("data-testid=\"nav-item-catalogs\"")
    }

    @Test
    fun `quality filtered to a catalog is inside that catalog, and not in the tenant nav`() {
        val tenant = createTenant("Nav Quality")
        withMediator {
            CreateCatalog(tenant.id, CatalogKey.of("letters"), "Letters").execute()
            SaveFeatureToggle(tenant.id, KnownFeatures.QUALITY, enabled = true).execute()
        }

        val quality = restTemplate.getForEntity("/tenants/${tenant.id.value}/catalogs/${tenant.id.value}/letters/quality", String::class.java).body!!
        assertThat(quality.substringAfter("data-testid=\"ws-crumb-catalog\"").substringBefore("</button>")).contains(">Letters<")
        assertThat(quality.replace(Regex("\\s+"), " ")).contains("class=\"ws-nav-item active\" data-testid=\"ws-nav-quality\"")

        val outside = restTemplate.getForEntity("/tenants/${tenant.id.value}/environments", String::class.java).body!!
        assertThat(outside).doesNotContain("nav-item-quality")
    }

    @Test
    fun `shared chrome renders the application display name`() {
        val tenant = createTenant("Nav Display Name")

        val body = restTemplate.getForEntity("/tenants/${tenant.id.value}/catalogs", String::class.java).body!!
        val navUser = body.substringAfter("""class="ws-account-name"""").substringBefore("</span>")
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
            SaveFeatureToggle(tenant.id, KnownFeatures.SUPPORT_COMPATIBILITY_CHECK, enabled = false).execute()
        }

        val body = restTemplate.getForEntity("/tenants/${tenant.id.value}/catalogs", String::class.java).body!!

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

        val body = restTemplate.getForEntity("/tenants/${tenant.id.value}/catalogs", String::class.java).body!!

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

        // On by default; switching the toggle off is the only thing that hides it.
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

        withMediator {
            SaveFeatureToggle(tenant.id, KnownFeatures.RESOURCE_RELOCATION, enabled = false).execute()
        }

        val without = restTemplate.getForEntity("/tenants/${tenant.id.value}/catalogs", String::class.java).body!!
        assertThat(without).doesNotContain("nav-item-catalog-organise")
    }

    @Test
    fun `a stable feature's nav item carries no maturity badge`() {
        val tenant = createTenant("Nav Stable")

        val body = restTemplate.getForEntity("/tenants/${tenant.id.value}/catalogs", String::class.java).body!!.replace(Regex("\\s+"), " ")

        // Organising catalogs is stable: its item is there, and no stage badge follows its label.
        val organise = Regex("""<a[^>]*nav-item-catalog-organise.*?</a>""").find(body)?.value
        assertThat(organise).isNotNull().doesNotContain("badge-alpha").doesNotContain("badge-beta")
    }
}
