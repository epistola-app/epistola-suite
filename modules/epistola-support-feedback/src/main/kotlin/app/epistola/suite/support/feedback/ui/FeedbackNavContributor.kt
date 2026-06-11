package app.epistola.suite.support.feedback.ui

import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveAvailableFeatures
import app.epistola.suite.htmx.UiRequestContext
import app.epistola.suite.htmx.nav.NavContributor
import app.epistola.suite.htmx.nav.NavItem
import app.epistola.suite.mediator.query
import org.springframework.stereotype.Component

/**
 * Adds the Feedback item to the Support nav group when the `support-feedback` feature is available —
 * the local toggle is on **and** the installation is entitled to it (hub-gated).
 */
@Component
class FeedbackNavContributor : NavContributor {
    override fun items(context: UiRequestContext): List<NavItem> {
        val available = ResolveAvailableFeatures(context.tenantKey).query()
        if (available[KnownFeatures.SUPPORT_FEEDBACK] != true) return emptyList()
        return listOf(NavItem("support", "feedback", "Feedback", "feedback", order = 10))
    }
}
