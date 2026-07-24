// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.config.findByTenantAndId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

data class GetCatalog(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<Catalog?>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class GetCatalogHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetCatalog, Catalog?> {

    override fun handle(query: GetCatalog): Catalog? = jdbi.withHandle<Catalog?, Exception> { handle ->
        handle.findByTenantAndId<Catalog>("catalogs", query.tenantKey, query.catalogKey.value)
    }
}
