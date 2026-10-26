// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CATALOG_RELEASE_JOIN
import app.epistola.suite.catalog.CATALOG_ROW_COLUMNS
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
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
        // With its current release, a query over its released rows (ADR 0036).
        handle.createQuery("SELECT $CATALOG_ROW_COLUMNS FROM catalogs c $CATALOG_RELEASE_JOIN WHERE c.tenant_key = :t ORDER BY c.name")
            .bind("t", query.tenantKey)
            .mapTo<Catalog>()
            .list()
    }
}
