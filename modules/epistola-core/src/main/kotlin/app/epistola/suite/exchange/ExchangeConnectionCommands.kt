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
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

data class StartExchangeConnection(
    override val tenantKey: TenantKey,
    val redirectUri: String,
) : Command<String>,
    RequiresPermission {
    override val permission = Permission.TENANT_SETTINGS
}

data class CompleteExchangeConnection(
    override val tenantKey: TenantKey,
    val state: String,
    val code: String,
    val oauthApplicationId: UUID,
    val issuer: String,
) : Command<ExchangeTenantConnection>,
    RequiresPermission {
    override val permission = Permission.TENANT_SETTINGS
}

data class DisconnectExchangeConnection(
    override val tenantKey: TenantKey,
    val forgetLocally: Boolean = false,
) : Command<Unit>,
    RequiresPermission {
    override val permission = Permission.TENANT_SETTINGS
}

@Component
class StartExchangeConnectionHandler(
    private val jdbi: Jdbi,
    private val client: ExchangeClient,
    private val installationService: InstallationService,
    private val properties: ExchangeProperties,
) : CommandHandler<StartExchangeConnection, String> {
    private val random = SecureRandom()

    override fun handle(command: StartExchangeConnection): String {
        require(properties.enabled) { "Exchange publishing is disabled for this deployment" }
        val tenant = requireNotNull(GetTenant(command.tenantKey).query())
        val existing = jdbi.withHandle<ExchangeTenantConnection?, Exception> { handle -> current(handle, command.tenantKey) }
        val endpoints = client.endpoints()
        val state = randomValue(32)
        val verifier = randomValue(64)
        val response = client.startAuthorization(
            endpoints,
            tenant.name,
            installationService.get().id.toString(),
            command.redirectUri,
            state,
            base64Sha256(verifier),
            existing?.oauthApplicationId,
            existing?.tenantConnectionId,
        )
        val now = EpistolaClock.offsetDateTime()
        jdbi.useHandle<Exception> { handle ->
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
                INSERT INTO exchange_oauth_authorizations (tenant_key, state_hash, code_verifier, redirect_uri, expires_at)
                VALUES (:tenantKey, :stateHash, :codeVerifier, :redirectUri, :expiresAt)
                ON CONFLICT (tenant_key) DO UPDATE SET state_hash = EXCLUDED.state_hash,
                    code_verifier = EXCLUDED.code_verifier, redirect_uri = EXCLUDED.redirect_uri,
                    expires_at = EXCLUDED.expires_at, created_at = NOW()
                """,
            ).bind("tenantKey", command.tenantKey).bind("stateHash", sha256(state))
                .bind("codeVerifier", Secret(verifier)).bind("redirectUri", command.redirectUri)
                .bind("expiresAt", now.plus(response.expiresIn)).execute()
        }
        return response.authorizationUri
    }

    private fun randomValue(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)
}

@Component
class CompleteExchangeConnectionHandler(
    private val jdbi: Jdbi,
    private val client: ExchangeClient,
) : CommandHandler<CompleteExchangeConnection, ExchangeTenantConnection> {
    override fun handle(command: CompleteExchangeConnection): ExchangeTenantConnection = try {
        complete(command)
    } catch (failure: HttpClientErrorException.Unauthorized) {
        if (!failure.responseBodyAsString.contains("\"error\":\"invalid_client\"")) throw failure
        credentialRecoveryRequired(command.tenantKey)
    }

    private fun complete(command: CompleteExchangeConnection): ExchangeTenantConnection = jdbi.inTransaction<ExchangeTenantConnection, Exception> { handle ->
        val pending = handle.createQuery(
            "SELECT * FROM exchange_oauth_authorizations WHERE tenant_key = :tenantKey FOR UPDATE",
        ).bind("tenantKey", command.tenantKey).mapTo<ExchangeAuthorizationTransaction>().one()
        require(pending.stateHash == sha256(command.state)) { "Exchange authorization state does not match" }
        require(pending.expiresAt > EpistolaClock.offsetDateTime()) { "Exchange authorization expired" }
        val connection = current(handle, command.tenantKey) ?: error("Exchange connection state is missing")
        require(connection.issuer.trimEnd('/') == command.issuer.trimEnd('/')) { "Exchange authorization issuer does not match" }
        val endpoints = ExchangeEndpoints(
            connection.issuer,
            connection.baseUrl,
            "${connection.issuer}/oauth/authorization-requests",
            "${connection.issuer}/oauth/token",
        )
        val token = client.exchangeAuthorizationCode(
            endpoints,
            command.code,
            command.oauthApplicationId,
            pending.redirectUri,
            pending.codeVerifier.value,
            connection.clientSecret?.value,
        )
        require(token.oauthApplicationId == command.oauthApplicationId) { "Exchange token application does not match" }
        val context = client.context(endpoints, token.accessToken)
        val now = EpistolaClock.offsetDateTime()
        handle.createUpdate(
            """
                UPDATE exchange_tenant_connections SET tenant_connection_id = :connectionId,
                    tenant_connection_reference = :connectionReference,
                    organization_slug = :organizationSlug, organization_name = :organizationName,
                    oauth_application_id = :applicationId,
                    client_secret = COALESCE(:clientSecret, client_secret),
                    scopes = :scopes, namespaces = :namespaces,
                    default_namespace = COALESCE(:soleNamespace, default_namespace),
                    access_token = :accessToken, access_token_expires_at = :accessExpiresAt,
                    refresh_token = :refreshToken, refresh_token_expires_at = :refreshExpiresAt,
                    status = 'ACTIVE', last_error = NULL, updated_at = NOW()
                WHERE tenant_key = :tenantKey
                """,
        ).bind("connectionId", context.tenantConnectionId).bind("connectionReference", context.tenantConnectionReference)
            .bind("organizationSlug", context.organizationSlug).bind("organizationName", context.organizationName)
            .bind("applicationId", token.oauthApplicationId).bind("clientSecret", token.clientSecret?.let(::Secret))
            .bind("scopes", context.scopes.toTypedArray()).bind("namespaces", context.namespaces.toTypedArray())
            .bind("soleNamespace", context.namespaces.singleOrNull()).bind("accessToken", Secret(token.accessToken))
            .bind("accessExpiresAt", now.plus(token.accessTokenExpiresIn)).bind("refreshToken", Secret(token.refreshToken))
            .bind("refreshExpiresAt", now.plus(token.refreshTokenExpiresIn)).bind("tenantKey", command.tenantKey).execute()
        handle.createUpdate("DELETE FROM exchange_oauth_authorizations WHERE tenant_key = :tenantKey")
            .bind("tenantKey", command.tenantKey).execute()
        requireNotNull(current(handle, command.tenantKey))
    }

    private fun credentialRecoveryRequired(tenantKey: TenantKey): ExchangeTenantConnection = jdbi.inTransaction<ExchangeTenantConnection, Exception> { handle -> recordCredentialRecovery(handle, tenantKey) }

    private fun recordCredentialRecovery(
        handle: Handle,
        tenantKey: TenantKey,
    ): ExchangeTenantConnection {
        handle.createUpdate(
            """
                UPDATE exchange_tenant_connections
                SET status = 'REAUTHORIZATION_REQUIRED',
                    last_error = :error,
                    updated_at = NOW()
                WHERE tenant_key = :tenantKey
                """,
        ).bind(
            "error",
            "Exchange rejected the application credentials. Connect again and select " +
                "‘Recover application credentials and revoke its previous tokens’ during authorization.",
        ).bind("tenantKey", tenantKey).execute()
        handle.createUpdate("DELETE FROM exchange_oauth_authorizations WHERE tenant_key = :tenantKey")
            .bind("tenantKey", tenantKey).execute()
        return requireNotNull(current(handle, tenantKey))
    }
}

@Component
class DisconnectExchangeConnectionHandler(
    private val jdbi: Jdbi,
    private val client: ExchangeClient,
) : CommandHandler<DisconnectExchangeConnection, Unit> {
    override fun handle(command: DisconnectExchangeConnection) {
        val connection = jdbi.withHandle<ExchangeTenantConnection?, Exception> { handle -> current(handle, command.tenantKey) }
        if (!command.forgetLocally && connection?.tenantConnectionId != null) {
            require(connection.status == ExchangeConnectionStatus.ACTIVE) {
                "Reauthorize the Exchange connection before disconnecting it, or explicitly forget it locally"
            }
            val now = EpistolaClock.offsetDateTime()
            val accessToken = if (connection.accessTokenExpiresAt?.isAfter(now.plusSeconds(30)) == true) {
                requireNotNull(connection.accessToken).value
            } else {
                client.refresh(
                    ExchangeEndpoints(
                        connection.issuer,
                        connection.baseUrl,
                        "${connection.issuer}/oauth/authorization-requests",
                        "${connection.issuer}/oauth/token",
                    ),
                    requireNotNull(connection.refreshToken).value,
                    requireNotNull(connection.oauthApplicationId),
                    requireNotNull(connection.clientSecret).value,
                ).accessToken
            }
            client.disconnect(connection.baseUrl, accessToken)
        }
        jdbi.useTransaction<Exception> { handle ->
            handle.createUpdate("DELETE FROM exchange_oauth_authorizations WHERE tenant_key = :tenantKey")
                .bind("tenantKey", command.tenantKey).execute()
            handle.createUpdate("DELETE FROM exchange_tenant_connections WHERE tenant_key = :tenantKey")
                .bind("tenantKey", command.tenantKey).execute()
        }
    }
}

private fun current(handle: Handle, tenantKey: TenantKey): ExchangeTenantConnection? = handle.createQuery("SELECT * FROM exchange_tenant_connections WHERE tenant_key = :tenantKey")
    .bind("tenantKey", tenantKey).mapTo<ExchangeTenantConnection>().findOne().orElse(null)

internal fun sha256(value: String): String = HexFormat.of().formatHex(
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.US_ASCII)),
)

private fun base64Sha256(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.US_ASCII)),
)
