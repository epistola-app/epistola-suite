// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.exchange.CompleteExchangeConnection
import app.epistola.suite.exchange.DisconnectExchangeConnection
import app.epistola.suite.exchange.ExchangeProperties
import app.epistola.suite.exchange.FindExchangeAuthorizationTenant
import app.epistola.suite.exchange.GetExchangeConnection
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
import java.net.URI
import java.util.UUID

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
        }
    }

    fun connect(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        requirePermission(tenantKey, Permission.TENANT_SETTINGS)
        val authorizationUri = StartExchangeConnection(tenantKey, callbackUri(request)).execute()
        return ServerResponse.status(303).header("Location", authorizationUri).build()
    }

    fun callback(request: ServerRequest): ServerResponse {
        val state = request.requiredParam("state")
        val tenantKey = requireNotNull(FindExchangeAuthorizationTenant(state).query()) {
            "Exchange authorization is unknown or expired"
        }
        requirePermission(tenantKey, Permission.TENANT_SETTINGS)
        CompleteExchangeConnection(
            tenantKey,
            state,
            request.requiredParam("code"),
            UUID.fromString(request.requiredParam("client_id")),
            request.requiredParam("iss"),
        ).execute()
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

    fun disconnect(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        requirePermission(tenantKey, Permission.TENANT_SETTINGS)
        DisconnectExchangeConnection(
            tenantKey,
            forgetLocally = request.params().getFirst("forgetLocal") == "true",
        ).execute()
        return redirect(tenantKey.value)
    }

    private fun callbackUri(request: ServerRequest): String = properties.callbackUrl?.trim()?.takeIf(String::isNotEmpty)
        ?: URI(request.uri().scheme, request.uri().authority, "/oauth/exchange/callback", null, null).toString()

    private fun ServerRequest.requiredParam(name: String): String = param(name).orElse(null)?.takeIf(String::isNotBlank)
        ?: error("Exchange callback is missing '$name'")

    private fun redirect(tenant: String): ServerResponse = ServerResponse.status(303)
        .header("Location", "/tenants/$tenant/exchange")
        .build()
}
