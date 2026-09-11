// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogUpstreamCheck
import app.epistola.suite.catalog.CatalogUpstreamCheckStore
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.SystemInternal
import org.springframework.stereotype.Component

/**
 * What every subscribed catalog's source was last seen to offer.
 *
 * One query for a whole page, and no remote call: the catalogs list renders update badges from what
 * the background check already recorded. Before this, that badge could only appear after a
 * round-trip made while the page was rendering, once per row.
 */
data class GetCatalogUpstreamStates(
    override val tenantKey: TenantKey,
) : Query<Map<CatalogKey, CatalogUpstreamCheck>>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class GetCatalogUpstreamStatesHandler(
    private val store: CatalogUpstreamCheckStore,
) : QueryHandler<GetCatalogUpstreamStates, Map<CatalogKey, CatalogUpstreamCheck>> {
    override fun handle(query: GetCatalogUpstreamStates): Map<CatalogKey, CatalogUpstreamCheck> = store.statesFor(query.tenantKey)
}

/**
 * How many catalogs have an upgrade waiting, for the navigation badge and the home notice.
 *
 * `SystemInternal` because it renders as page chrome for any signed-in user, the same reason
 * `ResolveFeatureToggles` is. Callers still check [Permission.CATALOG_VIEW] before showing anything
 * — a count is only chrome if the reader is allowed to act on it.
 *
 * This runs on every page render, so it stays one indexed count over already-recorded state.
 */
data class CountCatalogsWithUpgradeAvailable(val tenantKey: TenantKey) :
    Query<Int>,
    SystemInternal

@Component
class CountCatalogsWithUpgradeAvailableHandler(
    private val store: CatalogUpstreamCheckStore,
) : QueryHandler<CountCatalogsWithUpgradeAvailable, Int> {
    override fun handle(query: CountCatalogsWithUpgradeAvailable): Int = store.countUpgradesAvailable(query.tenantKey)
}
