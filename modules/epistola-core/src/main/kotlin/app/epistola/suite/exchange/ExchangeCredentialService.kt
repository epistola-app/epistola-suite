// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.crypto.Secret
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import java.time.Duration

/**
 * A tenant's enrollment reduced to what a UI may know about it: never the credentials.
 *
 * [organizationSlug] is null only between starting an authorization and completing it, which is why
 * links into Exchange are built from it rather than assumed to be there.
 */
data class ExchangeConnectionSummary(
    val baseUrl: String,
    val organizationSlug: String?,
)

/**
 * Keeps each tenant's Exchange access token usable.
 *
 * The refresh round-trip happens **outside** any database transaction: a remote call inside one
 * would hold a pooled connection — and, with a row lock, block every other reader of that
 * connection row — for as long as Exchange takes to answer. Instead the row is read, the network
 * call is made unlocked, and the result is written back under an optimistic check on the row
 * version it was read at, so a concurrent rotation cannot be clobbered. (The check cannot be the
 * refresh token itself: credential columns are encrypted with a fresh nonce per write, so two
 * ciphertexts of the same secret never compare equal.)
 */
@Component
class ExchangeCredentialService(
    private val jdbi: Jdbi,
    private val client: ExchangeClient,
    private val metrics: ExchangeMetrics,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun connection(tenantKey: TenantKey): ExchangeTenantConnection? = jdbi.withHandle<ExchangeTenantConnection?, Exception> { handle ->
        handle.createQuery("SELECT * FROM exchange_tenant_connections WHERE tenant_key = :tenantKey")
            .bind("tenantKey", tenantKey).mapTo<ExchangeTenantConnection>().findOne().orElse(null)
    }

    fun activeConnection(tenantKey: TenantKey): ExchangeTenantConnection? = connection(tenantKey)?.takeIf { it.status == ExchangeConnectionStatus.ACTIVE }

    /**
     * The non-secret facts about a tenant's enrollment: that it exists, and where it points.
     *
     * Deliberately a projection rather than the whole row. A caller that needs to know whether a
     * tenant is connected, or how to link into Exchange, should not cause the access token, refresh
     * token and application secret to be decrypted to answer it.
     */
    fun activeConnectionSummary(tenantKey: TenantKey): ExchangeConnectionSummary? = jdbi.withHandle<ExchangeConnectionSummary?, Exception> { handle ->
        handle.createQuery(
            "SELECT base_url, organization_slug FROM exchange_tenant_connections WHERE tenant_key = :tenantKey AND status = 'ACTIVE'",
        ).bind("tenantKey", tenantKey)
            .map { rs, _ -> ExchangeConnectionSummary(rs.getString("base_url"), rs.getString("organization_slug")) }
            .findOne().orElse(null)
    }

    /**
     * Returns a token good for at least [renewWithin], refreshing first if needed. Null when the
     * connection cannot produce one — the caller defers rather than failing the work.
     *
     * [renewWithin] is a parameter rather than a constant because the background sweep renews
     * further ahead than a caller that is about to use the token: with one shared margin, the
     * sweep would select connections and then decline to refresh every one of them.
     */
    fun accessToken(connection: ExchangeTenantConnection, renewWithin: Duration = USE_MARGIN): String? {
        val now = EpistolaClock.offsetDateTime()
        if (connection.accessToken != null && connection.accessTokenExpiresAt?.isAfter(now.plus(renewWithin)) == true) {
            return connection.accessToken.value
        }
        val refresh = connection.refreshToken ?: return null
        val applicationId = connection.oauthApplicationId ?: return null
        val clientSecret = connection.clientSecret ?: return null

        val token = try {
            client.refresh(connection.endpoints, refresh.value, applicationId, clientSecret.value)
        } catch (failure: HttpClientErrorException.BadRequest) {
            metrics.credentialRefresh(ExchangeMetrics.CredentialRefreshOutcome.REJECTED)
            markConnection(connection.tenantKey, ExchangeConnectionStatus.REAUTHORIZATION_REQUIRED, ExchangeFailureCode.REFRESH_TOKEN_REJECTED)
            return null
        } catch (failure: HttpClientErrorException.Unauthorized) {
            // Exchange will not accept the credentials at all. That is a state to recover from, not
            // an error to report: returning null lets the caller wait rather than spend a retry on
            // something no amount of retrying fixes.
            metrics.credentialRefresh(ExchangeMetrics.CredentialRefreshOutcome.REJECTED)
            markConnection(
                connection.tenantKey,
                ExchangeConnectionStatus.REAUTHORIZATION_REQUIRED,
                authorizationFailure(failure),
                failure.message,
            )
            return null
        } catch (failure: Exception) {
            metrics.credentialRefresh(ExchangeMetrics.CredentialRefreshOutcome.ERROR)
            throw failure
        }
        metrics.credentialRefresh(ExchangeMetrics.CredentialRefreshOutcome.RENEWED)

        val stored = jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE exchange_tenant_connections
                SET access_token = :accessToken, access_token_expires_at = :accessExpiresAt,
                    refresh_token = :refreshToken, refresh_token_expires_at = :refreshExpiresAt,
                    updated_at = NOW()
                WHERE tenant_key = :tenantKey AND updated_at = :readAt
                """,
            ).bind("accessToken", Secret(token.accessToken))
                .bind("accessExpiresAt", now.plus(token.accessTokenExpiresIn))
                .bind("refreshToken", Secret(token.refreshToken))
                .bind("refreshExpiresAt", now.plus(token.refreshTokenExpiresIn))
                .bind("tenantKey", connection.tenantKey)
                .bind("readAt", connection.updatedAt)
                .execute()
        }
        if (stored == 0) {
            // Another node rotated the same credential first; its token is the live one.
            logger.debug("Exchange refresh for tenant {} lost a rotation race; reloading", connection.tenantKey)
            return activeConnection(connection.tenantKey)?.accessToken?.value
        }
        return token.accessToken
    }

    /**
     * Refreshes tokens that are about to expire so enrollment stays usable even for tenants with no
     * queued work. Only connections that actually need it are touched.
     */
    fun refreshExpiringConnections() {
        // `access_token_expires_at` is written from the application clock, so it is compared
        // against the application clock — not the database's NOW().
        val deadline = EpistolaClock.offsetDateTime().plus(RENEW_AHEAD)
        val due = jdbi.withHandle<List<ExchangeTenantConnection>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT * FROM exchange_tenant_connections
                WHERE status = 'ACTIVE' AND refresh_token IS NOT NULL
                  AND (access_token_expires_at IS NULL OR access_token_expires_at <= :deadline)
                """,
            ).bind("deadline", deadline).mapTo<ExchangeTenantConnection>().list()
        }
        due.forEach { connection ->
            runCatching { accessToken(connection, renewWithin = RENEW_AHEAD) }.onFailure {
                logger.warn("Exchange credential refresh failed for tenant {}: {}", connection.tenantKey, it.message)
            }
        }
    }

    /** Installation-wide connection counts per state, for the leader-published gauges. */
    fun installationCountsByStatus(): Map<ExchangeConnectionStatus, Long> = jdbi.withHandle<Map<ExchangeConnectionStatus, Long>, Exception> { handle ->
        handle.createQuery("SELECT status, count(*) AS total FROM exchange_tenant_connections GROUP BY status")
            .map { rs, _ -> ExchangeConnectionStatus.valueOf(rs.getString("status")) to rs.getLong("total") }
            .list().toMap()
    }

    /**
     * Re-reads what Exchange currently grants this tenant and stores it, returning the namespaces.
     *
     * The granted list is otherwise only written when a tenant authorizes, and Exchange has no way
     * to tell Suite that an organization has withdrawn one — so between authorizations the local
     * picture can be confidently wrong. A refusal is the signal that it is, which is when this is
     * called: the failure repairs the state that caused it. Null when the connection cannot answer
     * at all, which is a different problem.
     */
    fun refreshGrants(tenantKey: TenantKey): Set<String>? {
        val connection = activeConnection(tenantKey) ?: return null
        val token = accessToken(connection) ?: return null
        val context = try {
            client.context(connection.endpoints, token)
        } catch (failure: Exception) {
            logger.warn("Could not re-read Exchange grants for tenant {}: {}", tenantKey, failure.message)
            return null
        }
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE exchange_tenant_connections
                SET scopes = :scopes, namespaces = :namespaces, updated_at = NOW()
                WHERE tenant_key = :tenantKey
                """,
            ).bind("scopes", context.scopes.toTypedArray()).bind("namespaces", context.namespaces.toTypedArray())
                .bind("tenantKey", tenantKey).execute()
        }
        return context.namespaces
    }

    /**
     * What Exchange's refusal means, in terms of what to do about it.
     *
     * The transport exception's own message is `401 Unauthorized: "{"error":"invalid_client"}"`,
     * which is accurate and useless: it is shown on the settings page, where the reader needs to
     * know whether to reconnect, wait, or call someone. `invalid_client` is worth telling apart —
     * it means Exchange no longer recognises the application at all, so an ordinary reconnect is
     * not enough and its credentials have to be reissued.
     */
    fun authorizationFailure(failure: HttpClientErrorException): ExchangeFailureCode = if (failure.responseBodyAsString.contains("\"error\":\"invalid_client\"")) {
        ExchangeFailureCode.APPLICATION_UNKNOWN
    } else {
        ExchangeFailureCode.CREDENTIALS_REJECTED
    }

    fun markConnection(
        tenantKey: TenantKey,
        status: ExchangeConnectionStatus,
        code: ExchangeFailureCode?,
        detail: String? = null,
    ) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate(
            """
            UPDATE exchange_tenant_connections
            SET status = :status, error_code = :code, error_detail = :detail, updated_at = NOW()
            WHERE tenant_key = :tenantKey
            """,
        ).bind("status", status).bind("code", code).bind("detail", detail).bind("tenantKey", tenantKey).execute()
    }

    private companion object {
        /** A caller about to use a token treats one expiring this soon as already expired. */
        val USE_MARGIN: Duration = Duration.ofSeconds(30)

        /** How far ahead the background sweep renews, so a token is never used at the wire. */
        val RENEW_AHEAD: Duration = Duration.ofMinutes(5)
    }
}
