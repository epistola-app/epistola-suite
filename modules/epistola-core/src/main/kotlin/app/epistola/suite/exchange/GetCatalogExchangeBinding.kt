// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

data class GetCatalogExchangeBinding(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<String?>,
    RequiresPermission {
    override val permission = Permission.CATALOG_VIEW
}

@Component
class GetCatalogExchangeBindingHandler(private val jdbi: Jdbi) : QueryHandler<GetCatalogExchangeBinding, String?> {
    override fun handle(query: GetCatalogExchangeBinding): String? = jdbi.withHandle<String?, Exception> { handle ->
        handle.createQuery(
            "SELECT namespace FROM catalog_exchange_bindings WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey",
        ).bind("tenantKey", query.tenantKey).bind("catalogKey", query.catalogKey)
            .mapTo(String::class.java).findOne().orElse(null)
    }
}
