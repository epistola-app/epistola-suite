// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.tenants.queries

import app.epistola.suite.common.TenantScoped
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.RequiresAuthentication
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.themes.TENANT_COLUMNS_WITH_THEME
import app.epistola.suite.themes.TENANT_THEME_JOIN
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetTenant(
    val id: TenantKey,
) : Query<Tenant?>,
    RequiresAuthentication,
    TenantScoped {
    override val tenantId: TenantKey get() = id
}

@Component
class GetTenantHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetTenant, Tenant?> {
    override fun handle(query: GetTenant): Tenant? = jdbi.withHandle<Tenant?, Exception> { handle ->
        handle
            .createQuery("SELECT $TENANT_COLUMNS_WITH_THEME FROM tenants t $TENANT_THEME_JOIN WHERE t.id = :id")
            .bind("id", query.id)
            .mapTo<Tenant>()
            .findOne()
            .orElse(null)
    }
}
