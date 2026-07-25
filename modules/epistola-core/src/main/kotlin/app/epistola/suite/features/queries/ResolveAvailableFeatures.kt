// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.features.queries

import app.epistola.suite.common.ids.FeatureKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.FeatureEntitlementGate
import app.epistola.suite.features.FeatureToggleService
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
 * When no [FeatureEntitlementGate] is present (OSS / support tier off) this degrades to the plain
 * toggle map — hub-only features are already off by default there (see [FeatureToggleService]).
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
        val gate = entitlementGate.ifAvailable ?: return toggles
        return toggles.mapValues { (feature, enabled) ->
            enabled && (feature !in gate.gatedFeatures || gate.isEntitled(feature, query.tenantKey))
        }
    }
}
