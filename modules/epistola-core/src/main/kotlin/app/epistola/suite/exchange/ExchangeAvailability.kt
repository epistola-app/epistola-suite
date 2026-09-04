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
 * The single place that answers what a tenant may do with Exchange right now — one definition per
 * direction, so no enforcement path, worker or UI surface re-combines the conditions itself.
 *
 * Each answer needs the same two switches: the hard, default-off deployment gate
 * (`epistola.exchange.enabled`), which is the network gate and applies either way, and the tenant's
 * own Alpha feature. The features are separate because the directions are:
 * `catalog-publishing` sends this tenant's content out, `catalog-installing` brings a third party's
 * in.
 *
 * Neither considers connection status, permission or namespace grants. Those are checked where they
 * are needed and mean different things per direction — publishing needs a namespace it may publish
 * into, installing needs nothing but a token — so folding them in here would make one answer that
 * is wrong for both.
 *
 * UI and other modules read these through [ResolveCatalogPublishingAvailability] and
 * [ResolveCatalogInstallAvailability] instead of injecting this service, matching how feature state
 * is read elsewhere.
 */
@Component
class ExchangeAvailability(private val properties: ExchangeProperties) {
    /** The deployment gate alone. Enough to decide whether to show setup UI at all. */
    val deploymentEnabled: Boolean get() = properties.enabled

    /** Requires a bound mediator context; the feature toggle is resolved through the mediator. */
    fun isAvailable(tenantKey: TenantKey): Boolean = properties.enabled &&
        ResolveAvailableFeatures(tenantKey).query()[KnownFeatures.CATALOG_PUBLISHING] == true

    /** May this tenant browse Exchange and install catalogs from it? */
    fun isInstallAvailable(tenantKey: TenantKey): Boolean = properties.enabled &&
        ResolveAvailableFeatures(tenantKey).query()[KnownFeatures.CATALOG_INSTALLING] == true
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

/** The inbound sibling of [ResolveCatalogPublishingAvailability]. */
data class ResolveCatalogInstallAvailability(val tenantKey: TenantKey) :
    Query<Boolean>,
    SystemInternal

@Component
class ResolveCatalogInstallAvailabilityHandler(
    private val availability: ExchangeAvailability,
) : QueryHandler<ResolveCatalogInstallAvailability, Boolean> {
    override fun handle(query: ResolveCatalogInstallAvailability): Boolean = availability.isInstallAvailable(query.tenantKey)
}
