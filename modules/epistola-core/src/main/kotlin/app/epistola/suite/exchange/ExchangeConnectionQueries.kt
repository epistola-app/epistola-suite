// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.SystemInternal
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetExchangeConnection(override val tenantKey: TenantKey) :
    Query<ExchangeTenantConnection?>,
    RequiresPermission {
    override val permission = Permission.TENANT_SETTINGS
}

data class FindExchangeAuthorizationTenant(
    val state: String,
) : Query<TenantKey?>,
    SystemInternal

@Component
class GetExchangeConnectionHandler(private val jdbi: Jdbi) : QueryHandler<GetExchangeConnection, ExchangeTenantConnection?> {
    override fun handle(query: GetExchangeConnection): ExchangeTenantConnection? = jdbi.withHandle<ExchangeTenantConnection?, Exception> { handle ->
        handle.createQuery("SELECT * FROM exchange_tenant_connections WHERE tenant_key = :tenantKey")
            .bind("tenantKey", query.tenantKey).mapTo<ExchangeTenantConnection>().findOne().orElse(null)
    }
}

@Component
class FindExchangeAuthorizationTenantHandler(
    private val jdbi: Jdbi,
) : QueryHandler<FindExchangeAuthorizationTenant, TenantKey?> {
    override fun handle(query: FindExchangeAuthorizationTenant): TenantKey? = jdbi.withHandle<TenantKey?, Exception> { handle ->
        handle.createQuery(
            "SELECT tenant_key FROM exchange_oauth_authorizations WHERE state_hash = :stateHash AND expires_at > NOW()",
        ).bind("stateHash", sha256(query.state)).mapTo<String>().findOne().map(TenantKey::of).orElse(null)
    }
}
