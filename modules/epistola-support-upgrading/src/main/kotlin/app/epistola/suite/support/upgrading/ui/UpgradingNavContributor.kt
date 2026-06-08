package app.epistola.suite.support.upgrading.ui

import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveFeatureToggles
import app.epistola.suite.htmx.UiRequestContext
import app.epistola.suite.htmx.nav.NavContributor
import app.epistola.suite.htmx.nav.NavItem
import app.epistola.suite.mediator.query
import org.springframework.stereotype.Component

/** Adds the Upgrading item to the Support nav group when the `support-upgrading` toggle is on. */
@Component
class UpgradingNavContributor : NavContributor {
    override fun items(context: UiRequestContext): List<NavItem> {
        val toggles = ResolveFeatureToggles(context.tenantKey).query()
        if (toggles[KnownFeatures.SUPPORT_UPGRADING] != true) return emptyList()
        return listOf(NavItem("support", "upgrading", "Upgrading", "upgrading", order = 30))
    }
}
