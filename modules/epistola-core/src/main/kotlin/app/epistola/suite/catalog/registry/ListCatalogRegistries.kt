// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.registry

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.springframework.stereotype.Component

/**
 * The container registries catalog releases can be published to and installed from (alpha). Names
 * and locations only; credentials never leave the configuration. Empty when none is configured,
 * which is what keeps the feature out of sight.
 */
data class ListCatalogRegistries(
    override val tenantKey: TenantKey,
) : Query<ConfiguredCatalogRegistries>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

data class ConfiguredCatalogRegistries(val registries: List<CatalogRegistrySummary>) {
    /** Whether the registry route is offered at all. */
    val available: Boolean get() = registries.isNotEmpty()
}

/** A registry as people see it: what to pick, and where it is ([location]: host, then the prefix). */
data class CatalogRegistrySummary(val name: String, val location: String)

@Component
class ListCatalogRegistriesHandler(
    private val registries: CatalogRegistries,
) : QueryHandler<ListCatalogRegistries, ConfiguredCatalogRegistries> {
    override fun handle(query: ListCatalogRegistries): ConfiguredCatalogRegistries = ConfiguredCatalogRegistries(registries.all().map { CatalogRegistrySummary(it.name, it.location) })
}
