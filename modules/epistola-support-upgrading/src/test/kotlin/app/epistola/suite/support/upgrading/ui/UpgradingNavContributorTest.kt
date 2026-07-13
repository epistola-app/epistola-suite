package app.epistola.suite.support.upgrading.ui

import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.htmx.UiRequestContext
import app.epistola.suite.htmx.nav.NavItem
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The Upgrading nav item is gated on the `support-compatibility-check` feature being available for
 * the tenant (local toggle; with the support tier off there is no entitlement gate on top).
 */
class UpgradingNavContributorTest : IntegrationTestBase() {
    @Autowired
    private lateinit var contributor: UpgradingNavContributor

    @Test
    fun `emits no item when the compatibility-check feature is off`() {
        val tenant = createTenant("Upgrading Nav Off")

        val items = withMediator {
            contributor.items(UiRequestContext(tenant.id, { true }))
        }

        assertThat(items).isEmpty()
    }

    @Test
    fun `emits the Upgrading item when the compatibility-check feature is available`() {
        val tenant = createTenant("Upgrading Nav On")
        withMediator {
            SaveFeatureToggle(
                tenantKey = tenant.id,
                featureKey = KnownFeatures.SUPPORT_COMPATIBILITY_CHECK,
                enabled = true,
            ).execute()
        }

        val items = withMediator {
            contributor.items(UiRequestContext(tenant.id, { true }))
        }

        assertThat(items).containsExactly(
            NavItem("support", "upgrading", "Upgrading", "upgrading", order = 30),
        )
    }

    @Test
    fun `feature availability is per tenant`() {
        val enabledTenant = createTenant("Upgrading Nav Tenant A")
        val disabledTenant = createTenant("Upgrading Nav Tenant B")
        withMediator {
            SaveFeatureToggle(
                tenantKey = enabledTenant.id,
                featureKey = KnownFeatures.SUPPORT_COMPATIBILITY_CHECK,
                enabled = true,
            ).execute()
        }

        withMediator {
            assertThat(contributor.items(UiRequestContext(enabledTenant.id, { true }))).hasSize(1)
            assertThat(contributor.items(UiRequestContext(disabledTenant.id, { true }))).isEmpty()
        }
    }
}
