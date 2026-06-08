package app.epistola.suite.support.feedback.ui

import app.epistola.suite.features.FeatureToggleService
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.htmx.UiRequestContext
import app.epistola.suite.htmx.footer.FooterContributor
import app.epistola.suite.htmx.footer.FooterFragment
import org.springframework.stereotype.Component

/** Injects the feedback FAB into the shell footer when the `support-feedback` toggle is on. */
@Component
class FeedbackFooterContributor(
    private val featureToggles: FeatureToggleService,
) : FooterContributor {
    override fun fragments(context: UiRequestContext): List<FooterFragment> {
        if (!featureToggles.isEnabled(context.tenantKey, KnownFeatures.SUPPORT_FEEDBACK)) return emptyList()
        return listOf(FooterFragment("feedback/footer-fab", "fab"))
    }
}
