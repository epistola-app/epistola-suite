package app.epistola.suite.support.backups.ui

import app.epistola.suite.features.FeatureToggleService
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.htmx.UiRequestContext
import app.epistola.suite.htmx.nav.NavContributor
import app.epistola.suite.htmx.nav.NavItem
import org.springframework.stereotype.Component

/** Adds the Backups item to the Support nav group when the `support-backups` toggle is on. */
@Component
class BackupsNavContributor(
    private val featureToggles: FeatureToggleService,
) : NavContributor {
    override fun items(context: UiRequestContext): List<NavItem> {
        if (!featureToggles.isEnabled(context.tenantKey, KnownFeatures.SUPPORT_BACKUPS)) return emptyList()
        return listOf(NavItem("support", "backups", "Backups", "backups", order = 20))
    }
}
