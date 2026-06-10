package app.epistola.suite.support.feedback.ui

import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveAvailableFeatures
import app.epistola.suite.htmx.UiRequestContext
import app.epistola.suite.htmx.footer.FooterContributor
import app.epistola.suite.htmx.footer.FooterFragment
import app.epistola.suite.mediator.query
import org.springframework.stereotype.Component

/**
 * Injects the feedback FAB into the shell footer when the `support-feedback` feature is available —
 * the local toggle is on **and** the installation is entitled to it (hub-gated).
 */
@Component
class FeedbackFooterContributor : FooterContributor {
    override fun fragments(context: UiRequestContext): List<FooterFragment> {
        val available = ResolveAvailableFeatures(context.tenantKey).query()
        if (available[KnownFeatures.SUPPORT_FEEDBACK] != true) return emptyList()
        return listOf(FooterFragment("feedback/footer-fab", "fab"))
    }
}
