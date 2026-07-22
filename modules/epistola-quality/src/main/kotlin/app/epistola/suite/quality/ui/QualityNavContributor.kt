package app.epistola.suite.quality.ui

import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.KnownFeatures.FeatureStage
import app.epistola.suite.features.queries.ResolveAvailableFeatures
import app.epistola.suite.htmx.UiRequestContext
import app.epistola.suite.htmx.nav.NavContributor
import app.epistola.suite.htmx.nav.NavItem
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import org.springframework.stereotype.Component

/**
 * Contributes **Quality** to the host's Authoring group — quality is about the templates being
 * authored, and it is an OSS feature, so it does not belong in the Support group. The Authoring
 * group is declared by the host's `CoreNavContributor`; the aggregator merges this item into it.
 *
 * Gated on the `quality` feature (alpha, off by default) *and* `TEMPLATE_VIEW`, so the item stays
 * hidden until an installation opts in. Resolved through [ResolveAvailableFeatures], the same
 * availability query used by quality's backend triggers. It is auth-bypassing by design: nav renders
 * for any signed-in user, and the permission-gated `GetFeatureToggles` would deny everyone who is
 * not a manager. The per-request cache makes the extra query free.
 */
@Component
class QualityNavContributor : NavContributor {
    override fun items(context: UiRequestContext): List<NavItem> {
        if (!context.hasPermission(Permission.TEMPLATE_VIEW)) return emptyList()
        if (ResolveAvailableFeatures(context.tenantKey).query()[KnownFeatures.QUALITY] != true) return emptyList()
        return listOf(
            NavItem(
                groupKey = "authoring",
                sectionKey = "quality",
                label = "Quality",
                pathSuffix = "quality",
                order = 50,
                // Read from the feature's own metadata rather than hardcoded, so promoting it out
                // of alpha is one edit in KnownFeatures and the badge follows.
                stage = KnownFeatures.metadata[KnownFeatures.QUALITY]?.stage ?: FeatureStage.STABLE,
            ),
        )
    }
}
