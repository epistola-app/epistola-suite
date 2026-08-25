// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.crypto.Secret
import java.time.OffsetDateTime
import java.util.UUID

enum class ExchangeConnectionStatus { PENDING, ACTIVE, REAUTHORIZATION_REQUIRED, BLOCKED }

data class ExchangeTenantConnection(
    val tenantKey: TenantKey,
    val tenantConnectionId: UUID?,
    val tenantConnectionReference: String?,
    val issuer: String,
    val baseUrl: String,
    val organizationSlug: String?,
    val organizationName: String?,
    val oauthApplicationId: UUID?,
    val clientSecret: Secret?,
    val scopes: Array<String> = emptyArray(),
    val namespaces: Array<String> = emptyArray(),
    val defaultNamespace: String?,
    val accessToken: Secret?,
    val accessTokenExpiresAt: OffsetDateTime?,
    val refreshToken: Secret?,
    val refreshTokenExpiresAt: OffsetDateTime?,
    val status: ExchangeConnectionStatus,
    val lastError: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

data class ExchangeAuthorizationTransaction(
    val tenantKey: TenantKey,
    val stateHash: String,
    val codeVerifier: Secret,
    val redirectUri: String,
    val expiresAt: OffsetDateTime,
    val createdAt: OffsetDateTime,
)
