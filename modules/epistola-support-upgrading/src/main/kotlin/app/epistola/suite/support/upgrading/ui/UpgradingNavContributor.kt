package app.epistola.suite.support.upgrading.ui

import app.epistola.suite.features.FeatureToggleService
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.htmx.UiRequestContext
import app.epistola.suite.htmx.nav.NavContributor
import app.epistola.suite.htmx.nav.NavItem
import org.springframework.stereotype.Component

/** Adds the Upgrading item to the Support nav group when the `support-upgrading` toggle is on. */
@Component
class UpgradingNavContributor(
    private val featureToggles: FeatureToggleService,
) : NavContributor {
    override fun items(context: UiRequestContext): List<NavItem> {
        if (!featureToggles.isEnabled(context.tenantKey, KnownFeatures.SUPPORT_UPGRADING)) return emptyList()
        return listOf(NavItem("support", "upgrading", "Upgrading", "upgrading", order = 30))
    }
}
