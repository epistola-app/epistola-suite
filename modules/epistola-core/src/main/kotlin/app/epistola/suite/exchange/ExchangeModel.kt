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
enum class ExchangeConnectionStatus(val label: String, val badgeClass: String, val guidance: String? = null) {
    PENDING("Not connected", "badge-muted"),
    ACTIVE("Connected", "badge-success"),
    REAUTHORIZATION_REQUIRED(
        "Reauthorization required",
        "badge-warning",
        "Exchange no longer accepts this tenant's stored credentials. Reconnecting restores the same " +
            "connection and queued publications resume where they left off.",
    ),
    BLOCKED(
        "Blocked by Exchange",
        "badge-error",
        "Exchange has refused this tenant's connection. It is not something Suite can resolve on its own — " +
            "the organization that owns the namespaces has to restore the tenant's access before publishing " +
            "can continue.",
    ),
    ;

    /**
     * Whether this state needs someone to do something, and therefore says what.
     *
     * The state is the headline, not whatever a failed call happened to report. A recorded error is
     * worth keeping and worth showing, but as supporting detail — on its own it tends to be the
     * transport's wording, which describes the symptom to nobody who can act on it.
     */
    val needsAttention: Boolean get() = guidance != null
}

/**
 * Lifecycle of one release in the publication outbox.
 *
 * A publication is only ever created once its catalog has a namespace, so there is no state for
 * "queued but with nowhere to go" — work that cannot move is not queued in the first place.
 *
 * [isActive] states are the worker's work set; [clearsArchive] states are the terminal
 * decisions after which the retained ZIP is no longer needed. `FAILED` deliberately keeps
 * its archive: an administrator can retry it with a fresh idempotency key.
 */
enum class CatalogPublicationStatus(val label: String, val badgeClass: String) {
    READY("Ready to submit", "badge-info"),
    SUBMITTED("Submitted", "badge-info"),
    RETRY("Retrying", "badge-warning"),
    ACCEPTED("Accepted", "badge-success"),
    REJECTED("Rejected", "badge-error"),
    FAILED("Failed", "badge-error"),
    CANCELLED("Cancelled", "badge-muted"),
    ;

    val isActive: Boolean get() = this == READY || this == SUBMITTED || this == RETRY

    val clearsArchive: Boolean get() = this == ACCEPTED || this == REJECTED

    /**
     * Whether an administrator may withdraw this publication.
     *
     * Anything Exchange has decided is already over. `SUBMITTED` is excluded for the opposite
     * reason: Exchange holds it and may still publish it, so dropping it locally would abandon an
     * outcome rather than prevent one.
     */
    val isCancellable: Boolean get() = this == READY || this == RETRY || this == FAILED

    companion object {
        val active: List<CatalogPublicationStatus> = entries.filter { it.isActive }

        /**
         * Maps Exchange's submission state onto the local lifecycle.
         *
         * Anything unrecognized stays in flight deliberately: Exchange may add an intermediate state
         * — scanning, queued behind another publisher — and treating a state we simply have not
         * heard of as a failure would break on its next release. Waiting for ever is prevented by
         * `epistola.exchange.submitted-timeout` instead, which bounds the wait whatever the reason.
         */
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
    /**
     * Why this connection is not usable, stored as the two columns it is read from.
     *
     * Flat rather than a nested value because this row is mapped by reflection, which builds a
     * property per column; [failure] composes them for anything that renders one.
     */
    val errorCode: String?,
    val errorDetail: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    val endpoints: ExchangeEndpoints
        get() = ExchangeEndpoints(issuer, baseUrl, authorizationRequestEndpoint, tokenEndpoint)

    /** The recorded failure, ready to render, or null while nothing has gone wrong. */
    val failure: ExchangeFailure? get() = ExchangeFailure.of(errorCode, errorDetail)
}

data class ExchangeAuthorizationTransaction(
    val tenantKey: TenantKey,
    val stateHash: String,
    val codeVerifier: Secret,
    val redirectUri: String,
    val expiresAt: OffsetDateTime,
    val createdAt: OffsetDateTime,
)
