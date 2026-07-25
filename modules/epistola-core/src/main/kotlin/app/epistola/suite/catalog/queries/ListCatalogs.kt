// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.Catalog
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.config.listForTenant
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

data class ListCatalogs(
    override val tenantKey: TenantKey,
) : Query<List<Catalog>>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class ListCatalogsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListCatalogs, List<Catalog>> {

    override fun handle(query: ListCatalogs): List<Catalog> = jdbi.withHandle<List<Catalog>, Exception> { handle ->
        handle.listForTenant<Catalog>("catalogs", query.tenantKey, orderBy = "name")
    }
}
