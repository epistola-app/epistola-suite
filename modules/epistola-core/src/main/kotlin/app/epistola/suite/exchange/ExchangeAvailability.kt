// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveAvailableFeatures
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.SystemInternal
import org.springframework.stereotype.Component

/**
 * The single place that answers "may this tenant publish to Exchange right now?".
 *
 * Two independent switches must both be on: the hard, default-off deployment gate
 * (`epistola.exchange.enabled`) and the tenant's Alpha `catalog-publishing` feature. Every
 * enforcement path, background worker, and UI surface resolves availability here rather than
 * re-combining the two conditions, so there is one definition to change.
 *
 * UI and other modules read it through [ResolveCatalogPublishingAvailability] instead of
 * injecting this service, matching how feature state is read elsewhere.
 */
@Component
class ExchangeAvailability(private val properties: ExchangeProperties) {
    /** The deployment gate alone. Enough to decide whether to show setup UI at all. */
    val deploymentEnabled: Boolean get() = properties.enabled

    /** Requires a bound mediator context; the feature toggle is resolved through the mediator. */
    fun isAvailable(tenantKey: TenantKey): Boolean = properties.enabled &&
        ResolveAvailableFeatures(tenantKey).query()[KnownFeatures.CATALOG_PUBLISHING] == true
}

/**
 * `SystemInternal` sibling of [ExchangeAvailability] for UI rendering (nav contributors, page
 * models) and background work — the same shape as `ResolveFeatureToggles`.
 */
data class ResolveCatalogPublishingAvailability(val tenantKey: TenantKey) :
    Query<Boolean>,
    SystemInternal

@Component
class ResolveCatalogPublishingAvailabilityHandler(
    private val availability: ExchangeAvailability,
) : QueryHandler<ResolveCatalogPublishingAvailability, Boolean> {
    override fun handle(query: ResolveCatalogPublishingAvailability): Boolean = availability.isAvailable(query.tenantKey)
}
