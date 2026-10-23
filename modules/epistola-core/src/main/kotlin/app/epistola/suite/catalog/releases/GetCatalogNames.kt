// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.CatalogAddress
import app.epistola.suite.catalog.CatalogName
import app.epistola.suite.common.TenantScoped
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.RequiresAuthentication
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * The full names (registry, namespace, key; contract ADR 0003) of the tenant's catalogs with
 * [catalogKeys], for a response that names a catalog it refers to by key, such as a generated
 * document. A key the tenant has no catalog by is left out, and so is one two catalogs share (ADR
 * 0034) unless the running work names one of them ([app.epistola.suite.catalog.CatalogNames]): a
 * response that knows the namespace names the catalog by it.
 *
 * Any member of the tenant may read them: a catalog's name is how the operations a member may do
 * already address it, so a document generator can be told which catalog a document came from without
 * viewing catalogs.
 */
data class GetCatalogNames(
    override val tenantKey: TenantKey,
    val catalogKeys: Collection<CatalogKey>,
) : Query<Map<CatalogKey, CatalogName>>,
    RequiresAuthentication,
    TenantScoped

@Component
class GetCatalogNamesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetCatalogNames, Map<CatalogKey, CatalogName>> {
    override fun handle(query: GetCatalogNames): Map<CatalogKey, CatalogName> {
        val keys = query.catalogKeys.map { it.value }.distinct()
        if (keys.isEmpty()) return emptyMap()
        return jdbi.withHandle<Map<CatalogKey, CatalogName>, Exception> { handle ->
            val found = handle.createQuery("SELECT catalog_key, registry, namespace FROM catalogs WHERE tenant_key = :t AND catalog_key IN (<keys>)")
                .bind("t", query.tenantKey)
                .bindList("keys", keys)
                .map { rs, _ -> CatalogName(rs.getString("registry"), rs.getString("namespace"), CatalogKey.of(rs.getString("catalog_key"))) }
                .list()
            found.groupBy { it.key }.mapNotNull { (key, names) ->
                CatalogAddress.lookUp(names.associateBy { CatalogAddress(it.namespace, key) }, key)?.let { key to it }
            }.toMap()
        }
    }
}
