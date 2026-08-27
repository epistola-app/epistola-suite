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
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun connection(tenantKey: TenantKey): ExchangeTenantConnection? = jdbi.withHandle<ExchangeTenantConnection?, Exception> { handle ->
        handle.createQuery("SELECT * FROM exchange_tenant_connections WHERE tenant_key = :tenantKey")
            .bind("tenantKey", tenantKey).mapTo<ExchangeTenantConnection>().findOne().orElse(null)
    }

    fun activeConnection(tenantKey: TenantKey): ExchangeTenantConnection? = connection(tenantKey)?.takeIf { it.status == ExchangeConnectionStatus.ACTIVE }

    /**
     * Returns a token good for at least [EXPIRY_MARGIN_SECONDS], refreshing first if needed. Null
     * when the connection cannot produce one — the caller defers rather than failing the work.
     */
    fun accessToken(connection: ExchangeTenantConnection): String? {
        val now = EpistolaClock.offsetDateTime()
        if (connection.accessToken != null && connection.accessTokenExpiresAt?.isAfter(now.plusSeconds(EXPIRY_MARGIN_SECONDS)) == true) {
            return connection.accessToken.value
        }
        val refresh = connection.refreshToken ?: return null
        val applicationId = connection.oauthApplicationId ?: return null
        val clientSecret = connection.clientSecret ?: return null

        val token = try {
            client.refresh(connection.endpoints, refresh.value, applicationId, clientSecret.value)
        } catch (failure: HttpClientErrorException.BadRequest) {
            markConnection(connection.tenantKey, ExchangeConnectionStatus.REAUTHORIZATION_REQUIRED, "Refresh token was rejected")
            return null
        }

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
        val due = jdbi.withHandle<List<ExchangeTenantConnection>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT * FROM exchange_tenant_connections
                WHERE status = 'ACTIVE' AND refresh_token IS NOT NULL
                  AND (access_token_expires_at IS NULL OR access_token_expires_at <= NOW() + :margin * INTERVAL '1 second')
                """,
            ).bind("margin", REFRESH_AHEAD_SECONDS).mapTo<ExchangeTenantConnection>().list()
        }
        due.forEach { connection ->
            runCatching { accessToken(connection) }.onFailure {
                logger.warn("Exchange credential refresh failed for tenant {}: {}", connection.tenantKey, it.message)
            }
        }
    }

    fun markConnection(tenantKey: TenantKey, status: ExchangeConnectionStatus, error: String?) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate(
            "UPDATE exchange_tenant_connections SET status = :status, last_error = :error, updated_at = NOW() WHERE tenant_key = :tenantKey",
        ).bind("status", status).bind("error", error).bind("tenantKey", tenantKey).execute()
    }

    private companion object {
        /** Treat a token expiring within this window as already expired. */
        const val EXPIRY_MARGIN_SECONDS = 30L

        /** How far ahead the background sweep renews. */
        const val REFRESH_AHEAD_SECONDS = 300L
    }
}
