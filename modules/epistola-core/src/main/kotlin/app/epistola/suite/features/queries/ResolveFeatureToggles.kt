// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.features.queries

import app.epistola.suite.common.ids.FeatureKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.FeatureToggleService
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.SystemInternal
import org.springframework.stereotype.Component

/**
 * Resolves a tenant's feature toggles for **internal** use — UI rendering (the nav/footer
 * contributors, shown to any signed-in user) and background schedulers.
 *
 * It is [SystemInternal] (bypasses authorization) because feature visibility must be resolvable
 * regardless of the caller's permissions, unlike [GetFeatureToggles], which is the permission-gated
 * (`TENANT_SETTINGS`) read backing the admin Features page. Both return the same resolved map and
 * share the per-request cache in [FeatureToggleService].
 */
data class ResolveFeatureToggles(
    val tenantKey: TenantKey,
) : Query<Map<FeatureKey, Boolean>>,
    SystemInternal

@Component
class ResolveFeatureTogglesHandler(
    private val featureToggleService: FeatureToggleService,
) : QueryHandler<ResolveFeatureToggles, Map<FeatureKey, Boolean>> {
    override fun handle(query: ResolveFeatureToggles): Map<FeatureKey, Boolean> = featureToggleService.resolveAll(query.tenantKey)
}
