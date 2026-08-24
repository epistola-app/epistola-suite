// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.crypto.Secret
import app.epistola.suite.installation.InstallationService
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class StartExchangeConnection(
    override val tenantKey: TenantKey,
) : Command<ExchangeDeviceAuthorization>,
    RequiresPermission {
    override val permission = Permission.TENANT_SETTINGS
}

data class PollExchangeConnection(
    override val tenantKey: TenantKey,
) : Command<ExchangeTenantConnection?>,
    RequiresPermission {
    override val permission = Permission.TENANT_SETTINGS
}

@Component
class StartExchangeConnectionHandler(
    private val jdbi: Jdbi,
    private val client: ExchangeClient,
    private val installationService: InstallationService,
    private val properties: ExchangeProperties,
) : CommandHandler<StartExchangeConnection, ExchangeDeviceAuthorization> {
    override fun handle(command: StartExchangeConnection): ExchangeDeviceAuthorization {
        require(properties.enabled) { "Exchange publishing is disabled for this deployment" }
        val tenant = requireNotNull(GetTenant(command.tenantKey).query())
        val existing = jdbi.withHandle<ExchangeTenantConnection?, Exception> { handle ->
            handle.createQuery("SELECT * FROM exchange_tenant_connections WHERE tenant_key = :tenantKey")
                .bind("tenantKey", command.tenantKey).mapTo<ExchangeTenantConnection>().findOne().orElse(null)
        }
        val endpoints = client.endpoints()
        val response = client.startDeviceAuthorization(
            endpoints,
            tenant.name,
            installationService.get().id.toString(),
            existing?.tenantConnectionId,
        )
        val now = EpistolaClock.offsetDateTime()
        return jdbi.withHandle<ExchangeDeviceAuthorization, Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO exchange_tenant_connections (tenant_key, issuer, base_url, tenant_connection_id, status)
                VALUES (:tenantKey, :issuer, :baseUrl, :connectionId, 'PENDING')
                ON CONFLICT (tenant_key) DO UPDATE SET issuer = EXCLUDED.issuer, base_url = EXCLUDED.base_url,
                    status = 'PENDING', last_error = NULL, updated_at = NOW()
                """,
            ).bind("tenantKey", command.tenantKey).bind("issuer", endpoints.issuer).bind("baseUrl", endpoints.baseUrl)
                .bind("connectionId", existing?.tenantConnectionId).execute()
            handle.createUpdate(
                """
                INSERT INTO exchange_device_authorizations
                    (tenant_key, device_code, user_code, verification_uri, verification_uri_complete,
                     expires_at, poll_interval_seconds, next_poll_at)
                VALUES (:tenantKey, :deviceCode, :userCode, :verificationUri, :verificationUriComplete,
                        :expiresAt, :interval, :nextPollAt)
                ON CONFLICT (tenant_key) DO UPDATE SET device_code = EXCLUDED.device_code,
                    user_code = EXCLUDED.user_code, verification_uri = EXCLUDED.verification_uri,
                    verification_uri_complete = EXCLUDED.verification_uri_complete,
                    expires_at = EXCLUDED.expires_at, poll_interval_seconds = EXCLUDED.poll_interval_seconds,
                    next_poll_at = EXCLUDED.next_poll_at, created_at = NOW()
                """,
            ).bind("tenantKey", command.tenantKey).bind("deviceCode", Secret(response.deviceCode))
                .bind("userCode", response.userCode).bind("verificationUri", response.verificationUri)
                .bind("verificationUriComplete", response.verificationUriComplete)
                .bind("expiresAt", now.plus(response.expiresIn)).bind("interval", response.interval.seconds.toInt())
                .bind("nextPollAt", now.plus(response.interval)).execute()
            handle.createQuery("SELECT * FROM exchange_device_authorizations WHERE tenant_key = :tenantKey")
                .bind("tenantKey", command.tenantKey).mapTo<ExchangeDeviceAuthorization>().one()
        }
    }
}

@Component
class PollExchangeConnectionHandler(
    private val jdbi: Jdbi,
    private val client: ExchangeClient,
) : CommandHandler<PollExchangeConnection, ExchangeTenantConnection?> {
    override fun handle(command: PollExchangeConnection): ExchangeTenantConnection? = jdbi.inTransaction<ExchangeTenantConnection?, Exception> { handle ->
        val pending = handle.createQuery(
            "SELECT * FROM exchange_device_authorizations WHERE tenant_key = :tenantKey FOR UPDATE",
        ).bind("tenantKey", command.tenantKey).mapTo<ExchangeDeviceAuthorization>().findOne().orElse(null)
            ?: return@inTransaction current(handle, command.tenantKey)
        val connection = current(handle, command.tenantKey) ?: error("Exchange connection state is missing")
        if (pending.expiresAt <= EpistolaClock.offsetDateTime()) error("Exchange device authorization expired")
        if (pending.nextPollAt > EpistolaClock.offsetDateTime()) return@inTransaction null
        val endpoints = ExchangeEndpoints(
            connection.issuer,
            connection.baseUrl,
            "${connection.issuer}/oauth/device/code",
            "${connection.issuer}/oauth/token",
        )
        val token = try {
            client.pollDeviceToken(endpoints, pending.deviceCode.value)
        } catch (_: ExchangeAuthorizationPendingException) {
            handle.createUpdate(
                "UPDATE exchange_device_authorizations SET next_poll_at = NOW() + (:seconds * INTERVAL '1 second') WHERE tenant_key = :tenantKey",
            ).bind("seconds", pending.pollIntervalSeconds).bind("tenantKey", command.tenantKey).execute()
            return@inTransaction null
        }
        val context = client.context(endpoints, token.accessToken)
        val now = EpistolaClock.offsetDateTime()
        handle.createUpdate(
            """
                UPDATE exchange_tenant_connections SET tenant_connection_id = :connectionId,
                    tenant_connection_reference = :connectionReference,
                    organization_slug = :organizationSlug, organization_name = :organizationName,
                    scopes = :scopes, namespaces = :namespaces,
                    default_namespace = COALESCE(:soleNamespace, default_namespace),
                    access_token = :accessToken, access_token_expires_at = :accessExpiresAt,
                    refresh_token = :refreshToken, refresh_token_expires_at = :refreshExpiresAt,
                    status = 'ACTIVE', last_error = NULL, updated_at = NOW()
                WHERE tenant_key = :tenantKey
                """,
        ).bind("connectionId", context.tenantConnectionId).bind("connectionReference", context.tenantConnectionReference)
            .bind("organizationSlug", context.organizationSlug).bind("organizationName", context.organizationName)
            .bind("scopes", context.scopes.toTypedArray())
            .bind("namespaces", context.namespaces.toTypedArray()).bind("soleNamespace", context.namespaces.singleOrNull())
            .bind("accessToken", Secret(token.accessToken))
            .bind("accessExpiresAt", now.plus(token.accessTokenExpiresIn)).bind("refreshToken", Secret(token.refreshToken))
            .bind("refreshExpiresAt", now.plus(token.refreshTokenExpiresIn)).bind("tenantKey", command.tenantKey).execute()
        handle.createUpdate("DELETE FROM exchange_device_authorizations WHERE tenant_key = :tenantKey")
            .bind("tenantKey", command.tenantKey).execute()
        current(handle, command.tenantKey)
    }

    private fun current(handle: org.jdbi.v3.core.Handle, tenantKey: TenantKey): ExchangeTenantConnection? = handle.createQuery("SELECT * FROM exchange_tenant_connections WHERE tenant_key = :tenantKey")
        .bind("tenantKey", tenantKey).mapTo<ExchangeTenantConnection>().findOne().orElse(null)
}
