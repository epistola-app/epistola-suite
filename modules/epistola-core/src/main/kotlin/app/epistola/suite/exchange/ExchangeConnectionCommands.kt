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
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/**
 * @param requestRedirectUri the callback derived from the browser request that started setup. The
 *   configured `epistola.exchange.callback-url` wins when a deployment sets one, so the UI never
 *   has to know about that override.
 */
data class StartExchangeConnection(
    override val tenantKey: TenantKey,
    val requestRedirectUri: String,
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

/**
 * Begins redirect authorization: discovers Exchange, asks it for an authorization URI, and stores
 * the single-use state hash plus the encrypted PKCE verifier.
 *
 * The discovered OAuth endpoints are persisted on the connection so every later token call uses
 * what the issuer actually advertised rather than a path this code assumed.
 */
@Component
class StartExchangeConnectionHandler(
    private val jdbi: Jdbi,
    private val client: ExchangeClient,
    private val installationService: InstallationService,
    private val availability: ExchangeAvailability,
    private val properties: ExchangeProperties,
) : CommandHandler<StartExchangeConnection, String> {
    private val random = SecureRandom()
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: StartExchangeConnection): String {
        // Enrolling is what creates the remote relationship, so it needs both switches — the
        // deployment gate and the tenant's own feature. Disconnecting deliberately does not, so a
        // tenant that has since turned the feature off can still clean up.
        validate("exchange", availability.deploymentEnabled, ValidationCode.PUBLICATION_UNAVAILABLE) {
            "Exchange publishing is disabled for this deployment."
        }
        validate("exchange", availability.isAvailable(command.tenantKey), ValidationCode.PUBLICATION_UNAVAILABLE) {
            "Enable the Catalog publishing feature for this tenant before connecting to Exchange."
        }
        val tenant = requireNotNull(GetTenant(command.tenantKey).query())
        val existing = jdbi.withHandle<ExchangeTenantConnection?, Exception> { handle -> current(handle, command.tenantKey) }
        val endpoints = client.endpoints()
        val state = randomValue(32)
        val verifier = randomValue(64)
        val redirectUri = properties.configuredCallbackUrl ?: command.requestRedirectUri
        val installation = installationService.get().id.toString()
        val startAuthorization = { application: UUID?, connection: UUID? ->
            client.startAuthorization(endpoints, tenant.name, installation, redirectUri, state, base64Sha256(verifier), application, connection)
        }
        // Reauthorizing offers the identity this tenant already holds, so Exchange renews it rather
        // than minting another and stranding the catalogs bound to it. Exchange may no longer have
        // it — rebuilt, restored from before the enrollment — and then the stored ids are simply
        // stale. Enrolling afresh is the recovery, and doing it here means the administrator is not
        // asked to work out that "forget locally, then connect" was what the dead end meant.
        var enrolledAfresh = false
        val response = try {
            startAuthorization(existing?.oauthApplicationId, existing?.tenantConnectionId)
        } catch (failure: HttpClientErrorException.BadRequest) {
            if (!failure.responseBodyAsString.contains("unknown_client_identity")) throw failure
            logger.warn("Exchange does not recognise the stored identity for tenant {}; enrolling afresh", command.tenantKey)
            enrolledAfresh = true
            startAuthorization(null, null)
        }
        val now = EpistolaClock.offsetDateTime()
        jdbi.useTransaction<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO exchange_tenant_connections
                    (tenant_key, issuer, base_url, authorization_request_endpoint, token_endpoint,
                     tenant_connection_id, status)
                VALUES (:tenantKey, :issuer, :baseUrl, :authorizationEndpoint, :tokenEndpoint, :connectionId, 'PENDING')
                ON CONFLICT (tenant_key) DO UPDATE SET issuer = EXCLUDED.issuer, base_url = EXCLUDED.base_url,
                    authorization_request_endpoint = EXCLUDED.authorization_request_endpoint,
                    token_endpoint = EXCLUDED.token_endpoint,
                    -- Starting an authorization is not the same as losing the current one. An
                    -- ACTIVE connection keeps working (and keeps publishing) until the new
                    -- authorization actually completes, so abandoning the browser flow cannot
                    -- strand a healthy tenant in PENDING with valid credentials.
                    status = CASE WHEN exchange_tenant_connections.status = 'ACTIVE' THEN 'ACTIVE' ELSE 'PENDING' END,
                    -- Enrolling afresh means the remote identity this row described is gone. Left
                    -- behind, the stale application secret would survive `COALESCE` when the new
                    -- authorization completes, and the connection would carry a credential for an
                    -- application that no longer exists.
                    tenant_connection_id = CASE WHEN :afresh THEN NULL ELSE exchange_tenant_connections.tenant_connection_id END,
                    oauth_application_id = CASE WHEN :afresh THEN NULL ELSE exchange_tenant_connections.oauth_application_id END,
                    client_secret = CASE WHEN :afresh THEN NULL ELSE exchange_tenant_connections.client_secret END,
                    last_error = NULL, updated_at = NOW()
                """,
            ).bind("tenantKey", command.tenantKey).bind("issuer", endpoints.issuer).bind("baseUrl", endpoints.baseUrl)
                .bind("authorizationEndpoint", endpoints.authorizationRequestEndpoint)
                .bind("tokenEndpoint", endpoints.tokenEndpoint)
                .bind("afresh", enrolledAfresh)
                .bind("connectionId", existing?.tenantConnectionId?.takeUnless { enrolledAfresh }).execute()
            handle.createUpdate(
                """
                INSERT INTO exchange_oauth_authorizations (tenant_key, state_hash, code_verifier, redirect_uri, expires_at)
                VALUES (:tenantKey, :stateHash, :codeVerifier, :redirectUri, :expiresAt)
                ON CONFLICT (tenant_key) DO UPDATE SET state_hash = EXCLUDED.state_hash,
                    code_verifier = EXCLUDED.code_verifier, redirect_uri = EXCLUDED.redirect_uri,
                    expires_at = EXCLUDED.expires_at, created_at = NOW()
                """,
            ).bind("tenantKey", command.tenantKey).bind("stateHash", sha256(state))
                .bind("codeVerifier", Secret(verifier)).bind("redirectUri", redirectUri)
                .bind("expiresAt", now.plus(response.expiresIn)).execute()
        }
        return response.authorizationUri
    }

    private fun randomValue(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)
}

/**
 * Finishes redirect authorization.
 *
 * Validation reads state, the token exchange and context lookup talk to Exchange, and only then is
 * the result written — the remote calls deliberately sit between two short transactions instead of
 * inside one, so Exchange's latency never holds a database connection.
 */
@Component
class CompleteExchangeConnectionHandler(
    private val jdbi: Jdbi,
    private val client: ExchangeClient,
) : CommandHandler<CompleteExchangeConnection, ExchangeTenantConnection> {

    override fun handle(command: CompleteExchangeConnection): ExchangeTenantConnection {
        val pending = jdbi.withHandle<ExchangeAuthorizationTransaction?, Exception> { handle ->
            handle.createQuery("SELECT * FROM exchange_oauth_authorizations WHERE tenant_key = :tenantKey")
                .bind("tenantKey", command.tenantKey).mapTo<ExchangeAuthorizationTransaction>().findOne().orElse(null)
        } ?: throw invalidAuthorization("No Exchange authorization is in progress.")
        val connection = jdbi.withHandle<ExchangeTenantConnection?, Exception> { handle -> current(handle, command.tenantKey) }
            ?: throw invalidAuthorization("Exchange connection state is missing.")

        validate("state", pending.stateHash == sha256(command.state), ValidationCode.EXCHANGE_AUTHORIZATION_INVALID) {
            "Exchange authorization state does not match."
        }
        validate("state", pending.expiresAt > EpistolaClock.offsetDateTime(), ValidationCode.EXCHANGE_AUTHORIZATION_INVALID) {
            "Exchange authorization expired."
        }
        validate(
            "issuer",
            connection.issuer.trimEnd('/') == command.issuer.trimEnd('/'),
            ValidationCode.EXCHANGE_AUTHORIZATION_INVALID,
        ) { "Exchange authorization issuer does not match." }

        val token = try {
            client.exchangeAuthorizationCode(
                connection.endpoints,
                command.code,
                command.oauthApplicationId,
                pending.redirectUri,
                pending.codeVerifier.value,
                connection.clientSecret?.value,
            )
        } catch (failure: HttpClientErrorException.Unauthorized) {
            if (!failure.responseBodyAsString.contains("\"error\":\"invalid_client\"")) throw failure
            return recordCredentialRecovery(command.tenantKey)
        }
        validate(
            "oauthApplicationId",
            token.oauthApplicationId == command.oauthApplicationId,
            ValidationCode.EXCHANGE_AUTHORIZATION_INVALID,
        ) { "Exchange token application does not match." }
        val context = client.context(connection.endpoints, token.accessToken)

        // A reauthorization renews an identity; it does not replace one. Every catalog binding in
        // this tenant names a namespace belonging to the organization it enrolled with, so silently
        // adopting a different organization would leave each of them pointing somewhere the tenant
        // no longer is — and the binding is immutable once a release has reached Exchange, so there
        // would be no way back. Moving organizations has to be a deliberate disconnect.
        val previousOrganization = connection.organizationSlug
        validate(
            "organization",
            previousOrganization == null || previousOrganization == context.organizationSlug,
            ValidationCode.EXCHANGE_ORGANIZATION_CHANGED,
        ) {
            "This tenant is connected to Exchange organization '$previousOrganization', but the " +
                "authorization returned '${context.organizationSlug}'. Disconnect first if you intend to move it."
        }

        val now = EpistolaClock.offsetDateTime()
        return jdbi.inTransaction<ExchangeTenantConnection, Exception> { handle ->
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
            clearPendingAuthorization(handle, command.tenantKey)
            requireNotNull(current(handle, command.tenantKey))
        }
    }

    /**
     * Exchange rejected the stored application credentials. That is not an unexpected error but a
     * recoverable state: the administrator reconnects and asks Exchange to rotate the application's
     * secret. The failed one-time authorization is discarded either way.
     */
    private fun recordCredentialRecovery(tenantKey: TenantKey): ExchangeTenantConnection = jdbi.inTransaction<ExchangeTenantConnection, Exception> { handle ->
        handle.createUpdate(
            """
            UPDATE exchange_tenant_connections
            SET status = 'REAUTHORIZATION_REQUIRED', last_error = :error, updated_at = NOW()
            WHERE tenant_key = :tenantKey
            """,
        ).bind(
            "error",
            "Exchange rejected the application credentials. Connect again and select " +
                "'Recover application credentials and revoke its previous tokens' during authorization.",
        ).bind("tenantKey", tenantKey).execute()
        clearPendingAuthorization(handle, tenantKey)
        requireNotNull(current(handle, tenantKey))
    }

    private fun invalidAuthorization(message: String) = ValidationException("state", message, ValidationCode.EXCHANGE_AUTHORIZATION_INVALID)
}

/**
 * Removes the tenant's enrollment. The normal path revokes remotely first; the local-only path is
 * the recovery action for an Exchange that cannot be reached at all.
 */
@Component
class DisconnectExchangeConnectionHandler(
    private val jdbi: Jdbi,
    private val client: ExchangeClient,
    private val credentials: ExchangeCredentialService,
    private val store: CatalogPublicationStore,
) : CommandHandler<DisconnectExchangeConnection, Unit> {
    override fun handle(command: DisconnectExchangeConnection) {
        val connection = credentials.connection(command.tenantKey)
        if (!command.forgetLocally && connection?.tenantConnectionId != null) {
            validate(
                "connection",
                connection.status == ExchangeConnectionStatus.ACTIVE,
                ValidationCode.EXCHANGE_CONNECTION_NOT_ACTIVE,
            ) { "Reauthorize the Exchange connection before disconnecting it, or explicitly forget it locally." }
            val accessToken = credentials.accessToken(connection)
                ?: throw ValidationException(
                    "connection",
                    "Could not obtain an Exchange token to revoke the connection; reauthorize, or forget it locally.",
                    ValidationCode.EXCHANGE_CONNECTION_NOT_ACTIVE,
                )
            client.disconnect(connection.baseUrl, accessToken)
        }
        jdbi.useTransaction<Exception> { handle ->
            clearPendingAuthorization(handle, command.tenantKey)
            handle.createUpdate("DELETE FROM exchange_tenant_connections WHERE tenant_key = :tenantKey")
                .bind("tenantKey", command.tenantKey).execute()
            // Queued work has just lost the credentials it needs. Left as-is it would be re-claimed
            // and deferred forever, holding a retained release archive each. Failing it stops that
            // and keeps the archives, so reconnecting and retrying still works.
            store.abandonActive(
                handle,
                command.tenantKey,
                "The Exchange connection was disconnected before this release was accepted.",
            )
        }
    }
}

private fun current(handle: Handle, tenantKey: TenantKey): ExchangeTenantConnection? = handle.createQuery("SELECT * FROM exchange_tenant_connections WHERE tenant_key = :tenantKey")
    .bind("tenantKey", tenantKey).mapTo<ExchangeTenantConnection>().findOne().orElse(null)

private fun clearPendingAuthorization(handle: Handle, tenantKey: TenantKey) {
    handle.createUpdate("DELETE FROM exchange_oauth_authorizations WHERE tenant_key = :tenantKey")
        .bind("tenantKey", tenantKey).execute()
}

internal fun sha256(value: String): String = HexFormat.of().formatHex(
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.US_ASCII)),
)

private fun base64Sha256(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.US_ASCII)),
)
