// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.exchange.ExchangeProperties
import app.epistola.suite.exchange.GetExchangeConnection
import app.epistola.suite.exchange.GetExchangeDeviceAuthorization
import app.epistola.suite.exchange.PollExchangeConnection
import app.epistola.suite.exchange.SetExchangeDefaultNamespace
import app.epistola.suite.exchange.StartExchangeConnection
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.requirePermission
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class ExchangeHandler(
    private val properties: ExchangeProperties,
) {
    fun settings(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        requirePermission(tenantKey, Permission.TENANT_SETTINGS)
        return ServerResponse.ok().page("exchange") {
            "pageTitle" to "Exchange - Epistola"
            "tenantId" to tenantKey
            "activeNavSection" to "exchange"
            "deploymentEnabled" to properties.enabled
            "connection" to GetExchangeConnection(tenantKey).query()
            "authorization" to GetExchangeDeviceAuthorization(tenantKey).query()
        }
    }

    fun connect(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        requirePermission(tenantKey, Permission.TENANT_SETTINGS)
        StartExchangeConnection(tenantKey).execute()
        return redirect(tenantKey.value)
    }

    fun poll(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        requirePermission(tenantKey, Permission.TENANT_SETTINGS)
        PollExchangeConnection(tenantKey).execute()
        return redirect(tenantKey.value)
    }

    fun setNamespace(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        requirePermission(tenantKey, Permission.TENANT_SETTINGS)
        SetExchangeDefaultNamespace(
            tenantKey,
            request.params().getFirst("namespace")?.trim().orEmpty(),
        ).execute()
        return redirect(tenantKey.value)
    }

    private fun redirect(tenant: String): ServerResponse = ServerResponse.status(303)
        .header("Location", "/tenants/$tenant/exchange")
        .build()
}
