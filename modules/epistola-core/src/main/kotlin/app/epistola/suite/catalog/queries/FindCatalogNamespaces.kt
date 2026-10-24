// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.common.TenantScoped
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.RequiresAuthentication
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * The tenant's catalogs with key [key], by address (ADR 0034, section 9), in namespace order: what an
 * address by key alone can mean. One catalog is what the key names, so the UI redirects to its
 * address; none or several, and the key alone names nothing.
 *
 * Any member of the tenant may ask: a catalog's address is how every operation a member may do
 * already names it.
 */
data class FindCatalogsByKey(
    override val tenantKey: TenantKey,
    val key: CatalogKey,
) : Query<CatalogsByKey>,
    RequiresAuthentication,
    TenantScoped

/** The catalogs [FindCatalogsByKey] found. */
data class CatalogsByKey(val catalogs: List<CatalogId>) {
    /** The one catalog the key names, or null when it names none or several. */
    val only: CatalogId? get() = catalogs.singleOrNull()
}

@Component
class FindCatalogsByKeyHandler(
    private val jdbi: Jdbi,
) : QueryHandler<FindCatalogsByKey, CatalogsByKey> {
    override fun handle(query: FindCatalogsByKey): CatalogsByKey = jdbi.withHandle<CatalogsByKey, Exception> { handle ->
        handle.createQuery("SELECT namespace FROM catalogs WHERE tenant_key = :t AND catalog_key = :k ORDER BY namespace")
            .bind("t", query.tenantKey)
            .bind("k", query.key)
            .mapTo(String::class.java)
            .list()
            .map { CatalogId(it, query.key, TenantId(query.tenantKey)) }
            .let(::CatalogsByKey)
    }
}
