// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetExchangeConnection(override val tenantKey: TenantKey) :
    Query<ExchangeTenantConnection?>,
    RequiresPermission {
    override val permission = Permission.TENANT_SETTINGS
}

data class GetExchangeDeviceAuthorization(
    override val tenantKey: TenantKey,
) : Query<ExchangeDeviceAuthorization?>,
    RequiresPermission {
    override val permission = Permission.TENANT_SETTINGS
}

@Component
class GetExchangeConnectionHandler(private val jdbi: Jdbi) : QueryHandler<GetExchangeConnection, ExchangeTenantConnection?> {
    override fun handle(query: GetExchangeConnection): ExchangeTenantConnection? = jdbi.withHandle<ExchangeTenantConnection?, Exception> { handle ->
        handle.createQuery("SELECT * FROM exchange_tenant_connections WHERE tenant_key = :tenantKey")
            .bind("tenantKey", query.tenantKey).mapTo<ExchangeTenantConnection>().findOne().orElse(null)
    }
}

@Component
class GetExchangeDeviceAuthorizationHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetExchangeDeviceAuthorization, ExchangeDeviceAuthorization?> {
    override fun handle(query: GetExchangeDeviceAuthorization): ExchangeDeviceAuthorization? = jdbi.withHandle<ExchangeDeviceAuthorization?, Exception> { handle ->
        handle.createQuery("SELECT * FROM exchange_device_authorizations WHERE tenant_key = :tenantKey")
            .bind("tenantKey", query.tenantKey).mapTo<ExchangeDeviceAuthorization>().findOne().orElse(null)
    }
}
