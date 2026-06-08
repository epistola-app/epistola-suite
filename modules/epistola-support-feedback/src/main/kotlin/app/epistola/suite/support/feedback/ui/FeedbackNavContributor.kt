package app.epistola.suite.support.feedback.ui

import app.epistola.suite.features.FeatureToggleService
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.htmx.UiRequestContext
import app.epistola.suite.htmx.nav.NavContributor
import app.epistola.suite.htmx.nav.NavItem
import org.springframework.stereotype.Component

/** Adds the Feedback item to the Support nav group when the `support-feedback` toggle is on. */
@Component
class FeedbackNavContributor(
    private val featureToggles: FeatureToggleService,
) : NavContributor {
    override fun items(context: UiRequestContext): List<NavItem> {
        if (!featureToggles.isEnabled(context.tenantKey, KnownFeatures.SUPPORT_FEEDBACK)) return emptyList()
        return listOf(NavItem("support", "feedback", "Feedback", "feedback", order = 10))
    }
}
