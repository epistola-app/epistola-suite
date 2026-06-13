package app.epistola.suite.features.queries

import app.epistola.suite.common.ids.FeatureKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.FeatureEntitlementGate
import app.epistola.suite.features.FeatureToggleService
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.SystemInternal
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * Resolves which features are **available** to a tenant: a feature is available when its local
 * toggle is on **and** — for hub-gated features — the installation is entitled to it. This is the
 * proactive composition the nav contributors and background schedulers consult so gated features are
 * hidden/skipped up front rather than only failing reactively at the hub.
 *
 * [SystemInternal] for the same reason as [ResolveFeatureToggles] (UI/scheduler use, auth-bypassing).
 * When no [FeatureEntitlementGate] is present (OSS / support tier off), the [KnownFeatures.SUPPORT_TIER]
 * features are unavailable regardless of their (on-by-default) toggle; other features fall back to the
 * plain toggle.
 */
data class ResolveAvailableFeatures(
    val tenantKey: TenantKey,
) : Query<Map<FeatureKey, Boolean>>,
    SystemInternal

@Component
class ResolveAvailableFeaturesHandler(
    private val featureToggleService: FeatureToggleService,
    private val entitlementGate: ObjectProvider<FeatureEntitlementGate>,
) : QueryHandler<ResolveAvailableFeatures, Map<FeatureKey, Boolean>> {
    override fun handle(query: ResolveAvailableFeatures): Map<FeatureKey, Boolean> {
        val toggles = featureToggleService.resolveAll(query.tenantKey)
        val gate = entitlementGate.ifAvailable
        return toggles.mapValues { (feature, enabled) ->
            if (gate != null) {
                enabled && (feature !in gate.gatedFeatures || gate.isEntitled(feature, query.tenantKey))
            } else {
                // Support tier off (OSS): tier features are unavailable regardless of their toggle
                // default; non-tier features fall back to the plain toggle.
                enabled && feature !in KnownFeatures.SUPPORT_TIER
            }
        }
    }
}
