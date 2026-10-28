// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CATALOG_RELEASE_JOIN
import app.epistola.suite.catalog.CATALOG_ROW_COLUMNS
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.common.PageRequest
import app.epistola.suite.common.Paging
import app.epistola.suite.common.ResultPage
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class ListCatalogs(
    override val tenantKey: TenantKey,
) : Query<List<Catalog>>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

/** [ListCatalogs], one page of it, cut in the database. */
data class ListCatalogPage(
    override val tenantKey: TenantKey,
    val page: PageRequest,
) : Query<ResultPage<Catalog>>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class ListCatalogsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListCatalogs, List<Catalog>> {
    override fun handle(query: ListCatalogs): List<Catalog> = jdbi.withHandle<List<Catalog>, Exception> { handle ->
        CatalogList.query(handle, query.tenantKey, paged = false).mapTo<Catalog>().list()
    }
}

@Component
class ListCatalogPageHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListCatalogPage, ResultPage<Catalog>> {
    override fun handle(query: ListCatalogPage): ResultPage<Catalog> = jdbi.withHandle<ResultPage<Catalog>, Exception> { handle ->
        Paging.read(query.page, Catalog::class.java) { CatalogList.query(handle, query.tenantKey, paged = true) }
    }
}

/** A tenant's catalogs by name, with their current release, a query over their released rows (ADR 0036). */
private object CatalogList {
    fun query(handle: Handle, tenantKey: TenantKey, paged: Boolean): org.jdbi.v3.core.statement.Query = handle.createQuery(
        "SELECT $CATALOG_ROW_COLUMNS${if (paged) ", ${Paging.TOTAL}" else ""} FROM catalogs c $CATALOG_RELEASE_JOIN " +
            "WHERE c.tenant_key = :t ORDER BY c.name, c.namespace, c.catalog_key${if (paged) " ${Paging.LIMIT_OFFSET}" else ""}",
    ).bind("t", tenantKey)
}
