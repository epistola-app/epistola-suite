package app.epistola.suite.support.ui

import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveFeatureToggles
import app.epistola.suite.htmx.UiRequestContext
import app.epistola.suite.htmx.nav.NavContributor
import app.epistola.suite.htmx.nav.NavGroup
import app.epistola.suite.htmx.nav.NavItem
import app.epistola.suite.mediator.query
import app.epistola.suite.support.ConditionalOnSupportModule
import org.springframework.stereotype.Component

/**
 * Owns the "Support" nav group and its Overview landing item.
 *
 * The base support module declares the group; the per-feature modules
 * (feedback/backups/upgrading) add their own items to it. The Overview item — and therefore the
 * whole group, since empty groups are dropped — appears only when at least one support feature is
 * enabled for the tenant, matching the previous "Support hidden unless ≥1 toggle on" behavior.
 */
@Component
@ConditionalOnSupportModule
class SupportNavContributor : NavContributor {

    override fun groups(context: UiRequestContext): List<NavGroup> = listOf(
        NavGroup(key = "support", label = "Support", order = 80, testId = "nav-dropdown-support"),
    )

    override fun items(context: UiRequestContext): List<NavItem> {
        val toggles = ResolveFeatureToggles(context.tenantKey).query()
        val anyFeatureOn = SUPPORT_FEATURES.any { toggles[it] == true }
        if (!anyFeatureOn) return emptyList()
        return listOf(NavItem("support", "overview", "Overview", "support", order = 0))
    }

    private companion object {
        val SUPPORT_FEATURES = listOf(
            KnownFeatures.SUPPORT_FEEDBACK,
            KnownFeatures.SUPPORT_BACKUPS,
            KnownFeatures.SUPPORT_UPGRADING,
        )
    }
}
