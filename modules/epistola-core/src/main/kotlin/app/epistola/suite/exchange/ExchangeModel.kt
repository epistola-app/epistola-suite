// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.crypto.Secret
import java.time.OffsetDateTime
import java.util.UUID

/**
 * State of a tenant's enrollment.
 *
 * [label] and [badgeClass] are the single wording and styling every surface uses, the same contract
 * as `KnownFeatures.FeatureStage`: a status without a matching `.badge-*` rule in the design system
 * would ship as an unstyled badge, so `ExchangeStatusBadgeTest` holds the two together.
 */
enum class ExchangeConnectionStatus(val label: String, val badgeClass: String) {
    PENDING("Not connected", "badge-muted"),
    ACTIVE("Connected", "badge-success"),
    REAUTHORIZATION_REQUIRED("Reauthorization required", "badge-warning"),
    BLOCKED("Blocked by Exchange", "badge-error"),
}

/**
 * Lifecycle of one release in the publication outbox.
 *
 * [isActive] states are the worker's work set; [clearsArchive] states are the terminal
 * decisions after which the retained ZIP is no longer needed. `FAILED` deliberately keeps
 * its archive: an administrator can retry it with a fresh idempotency key.
 */
enum class CatalogPublicationStatus(val label: String, val badgeClass: String) {
    WAITING_SETUP("Waiting for setup", "badge-muted"),
    READY("Ready to submit", "badge-info"),
    SUBMITTED("Submitted", "badge-info"),
    RETRY("Retrying", "badge-warning"),
    ACCEPTED("Accepted", "badge-success"),
    REJECTED("Rejected", "badge-error"),
    FAILED("Failed", "badge-error"),
    ;

    val isActive: Boolean get() = this == WAITING_SETUP || this == READY || this == SUBMITTED || this == RETRY

    val clearsArchive: Boolean get() = this == ACCEPTED || this == REJECTED

    companion object {
        val active: List<CatalogPublicationStatus> = entries.filter { it.isActive }

        /** Maps Exchange's submission state onto the local lifecycle; anything unrecognized stays in flight. */
        fun fromRemote(state: String): CatalogPublicationStatus = when (state) {
            "ACCEPTED" -> ACCEPTED
            "REJECTED" -> REJECTED
            "FAILED" -> FAILED
            else -> SUBMITTED
        }
    }
}

/**
 * One tenant's Exchange enrollment. [authorizationRequestEndpoint] and [tokenEndpoint] are
 * captured from the issuer's OAuth metadata when the connection is authorized, so later token
 * refreshes never reconstruct a hard-coded path.
 */
data class ExchangeTenantConnection(
    val tenantKey: TenantKey,
    val tenantConnectionId: UUID?,
    val tenantConnectionReference: String?,
    val issuer: String,
    val baseUrl: String,
    val authorizationRequestEndpoint: String,
    val tokenEndpoint: String,
    val organizationSlug: String?,
    val organizationName: String?,
    val oauthApplicationId: UUID?,
    val clientSecret: Secret?,
    val scopes: List<String> = emptyList(),
    val namespaces: List<String> = emptyList(),
    val defaultNamespace: String?,
    val accessToken: Secret?,
    val accessTokenExpiresAt: OffsetDateTime?,
    val refreshToken: Secret?,
    val refreshTokenExpiresAt: OffsetDateTime?,
    val status: ExchangeConnectionStatus,
    val lastError: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    val endpoints: ExchangeEndpoints
        get() = ExchangeEndpoints(issuer, baseUrl, authorizationRequestEndpoint, tokenEndpoint)
}

data class ExchangeAuthorizationTransaction(
    val tenantKey: TenantKey,
    val stateHash: String,
    val codeVerifier: Secret,
    val redirectUri: String,
    val expiresAt: OffsetDateTime,
    val createdAt: OffsetDateTime,
)
