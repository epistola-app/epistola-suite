// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CATALOG_RELEASE_JOIN
import app.epistola.suite.catalog.CATALOG_ROW_COLUMNS
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * The [limit] catalogs of a tenant changed most recently — their content, their details, or a
 * version published — newest first, for the tenant's side nav (docs/workspace-shell.md). The system
 * catalog, part of every tenant, is left out. Ordered and limited in the database.
 */
data class ListRecentCatalogs(
    override val tenantKey: TenantKey,
    val limit: Int = 5,
) : Query<List<Catalog>>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class ListRecentCatalogsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListRecentCatalogs, List<Catalog>> {

    override fun handle(query: ListRecentCatalogs): List<Catalog> = jdbi.withHandle<List<Catalog>, Exception> { handle ->
        handle.createQuery(
            // The latest change to anything in its current version (CATALOG_ACTIVITY_CTE), to its
            // details, or a version published.
            """
            $CATALOG_ACTIVITY_CTE
            SELECT $CATALOG_ROW_COLUMNS FROM catalogs c
            $CATALOG_RELEASE_JOIN
            $CATALOG_ACTIVITY_JOIN
            WHERE c.tenant_key = :t AND c.catalog_key <> :system
            ORDER BY GREATEST(COALESCE(a.last_activity, c.updated_at), c.updated_at, COALESCE(cr.released_at, c.updated_at)) DESC, c.name
            LIMIT :limit
            """,
        )
            .bind("t", query.tenantKey.value)
            .bind("system", SYSTEM_CATALOG_KEY.value)
            .bind("limit", query.limit)
            .mapTo<Catalog>()
            .list()
    }
}
