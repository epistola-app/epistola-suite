// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.exchange.CompleteExchangeConnection
import app.epistola.suite.exchange.DisconnectExchangeConnection
import app.epistola.suite.exchange.FindExchangeAuthorizationTenant
import app.epistola.suite.exchange.GetExchangeSettings
import app.epistola.suite.exchange.SetExchangeDefaultNamespace
import app.epistola.suite.exchange.StartExchangeConnection
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.requirePermission
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientException
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.net.URI
import java.util.UUID

@Component
class ExchangeHandler {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun settings(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        requirePermission(tenantKey, Permission.TENANT_SETTINGS)
        return renderSettings(tenantKey)
    }

    fun connect(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        requirePermission(tenantKey, Permission.TENANT_SETTINGS)
        return onSettingsPage(tenantKey) {
            val authorizationUri = StartExchangeConnection(tenantKey, callbackUri(request)).execute()
            ServerResponse.status(303).header("Location", authorizationUri).build()
        }
    }

    /**
     * Where Exchange sends the browser back. This is the likeliest place for setup to go wrong —
     * a reused or expired code, a mismatched application, an Exchange that errors — so once the
     * state identifies a tenant, every failure is shown on that tenant's settings page rather
     * than as an error page with no way forward.
     */
    fun callback(request: ServerRequest): ServerResponse {
        val state = request.requiredParam("state")
        val tenantKey = FindExchangeAuthorizationTenant(state).query()
            ?: throw ValidationException("state", "This Exchange authorization is unknown or has expired.", ValidationCode.EXCHANGE_AUTHORIZATION_INVALID)
        requirePermission(tenantKey, Permission.TENANT_SETTINGS)
        return onSettingsPage(tenantKey) {
            CompleteExchangeConnection(
                tenantKey,
                state,
                request.requiredParam("code"),
                request.requiredUuidParam("client_id"),
                request.requiredParam("iss"),
            ).execute()
            redirect(tenantKey.value)
        }
    }

    fun setNamespace(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        requirePermission(tenantKey, Permission.TENANT_SETTINGS)
        return onSettingsPage(tenantKey) {
            SetExchangeDefaultNamespace(
                tenantKey,
                request.params().getFirst("namespace")?.trim().orEmpty(),
            ).execute()
            redirect(tenantKey.value)
        }
    }

    fun disconnect(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        requirePermission(tenantKey, Permission.TENANT_SETTINGS)
        return onSettingsPage(tenantKey) {
            DisconnectExchangeConnection(
                tenantKey,
                forgetLocally = request.params().getFirst("forgetLocal") == "true",
            ).execute()
            redirect(tenantKey.value)
        }
    }

    /**
     * Every setup action belongs to one page, so a rejected one is shown there rather than
     * replacing the administrator's context with an error page.
     *
     * An unreachable or misbehaving Exchange is caught too, not just validation: "Exchange is
     * down" is the situation the guided-recovery design exists for, and it is the one where an
     * error page is least useful — the way out (reauthorize, or forget locally) is on this page.
     */
    private fun onSettingsPage(tenantKey: TenantKey, action: () -> ServerResponse): ServerResponse = try {
        action()
    } catch (failure: ValidationException) {
        renderSettings(tenantKey, failure.message)
    } catch (failure: RestClientException) {
        logger.warn("Exchange call failed for tenant {}: {}", tenantKey, failure.message)
        renderSettings(tenantKey, "Exchange could not be reached or refused the request. Try again, or forget the connection locally.")
    } catch (failure: IllegalStateException) {
        // The client raises these for a response that is present but unusable (missing fields,
        // empty body) — an Exchange problem, not ours.
        logger.warn("Exchange returned an unusable response for tenant {}: {}", tenantKey, failure.message)
        renderSettings(tenantKey, "Exchange returned a response Suite could not understand. Try again later.")
    }

    private fun renderSettings(tenantKey: TenantKey, error: String? = null): ServerResponse = ServerResponse.ok().page("exchange") {
        "pageTitle" to "Exchange - Epistola"
        "tenantId" to tenantKey
        "activeNavSection" to "exchange"
        "settings" to GetExchangeSettings(tenantKey).query()
        "error" to error
    }

    /** The callback as this browser reached us; a deployment may override it in configuration. */
    private fun callbackUri(request: ServerRequest): String = URI(request.uri().scheme, request.uri().authority, "/oauth/exchange/callback", null, null).toString()

    /** A malformed callback is a bad request from the browser, not a server fault. */
    private fun ServerRequest.requiredParam(name: String): String = param(name).orElse(null)?.takeIf(String::isNotBlank)
        ?: throw ValidationException(name, "The Exchange callback is missing '$name'.", ValidationCode.EXCHANGE_AUTHORIZATION_INVALID)

    private fun ServerRequest.requiredUuidParam(name: String): UUID = runCatching { UUID.fromString(requiredParam(name)) }
        .getOrElse { failure ->
            if (failure is ValidationException) throw failure
            throw ValidationException(name, "The Exchange callback's '$name' is not a valid identifier.", ValidationCode.EXCHANGE_AUTHORIZATION_INVALID)
        }

    private fun redirect(tenant: String): ServerResponse = ServerResponse.status(303)
        .header("Location", "/tenants/$tenant/exchange")
        .build()
}
