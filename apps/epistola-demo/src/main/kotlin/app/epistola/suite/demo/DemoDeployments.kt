// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.EnvironmentNotFoundException
import app.epistola.suite.environments.commands.DeployCatalogVersion
import app.epistola.suite.mediator.Mediator

/** The environments every demo tenant gets. */
internal val DEMO_ENVIRONMENTS = listOf("staging", "production")

/**
 * Deploys the installed demo catalog release to the demo environments, so generating against
 * `staging` or `production` works out of the box. Environments serve catalog releases; without this
 * they would serve nothing. Re-asserted on every boot and login, so an upgraded demo catalog reaches
 * the environments too: it is served beside the releases deployed before, and generation renders the
 * highest. The releases of other catalogs it renders with are deployed along.
 */
internal fun Mediator.deployDemoCatalog(tenantKey: TenantKey, catalogKey: CatalogKey, version: String) {
    for (environment in DEMO_ENVIRONMENTS) {
        try {
            send(DeployCatalogVersion(EnvironmentId(EnvironmentKey.of(environment), TenantId(tenantKey)), catalogKey, version, includeDependencies = true))
        } catch (_: EnvironmentNotFoundException) {
            // A tenant whose environments were removed by hand keeps it that way.
        }
    }
}
