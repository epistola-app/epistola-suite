package app.epistola.suite.apikeys

import app.epistola.suite.common.ids.ApiKeyId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserId
import java.time.Instant

/**
 * Domain model for an API key.
 *
 * API keys provide authentication for external systems accessing the REST API.
 * Each key belongs to a single tenant and acts as a Non-Personal Account (NPA)
 * at runtime — the key's own ID is used as the userId in EpistolaPrincipal.
 */
data class ApiKey(
    val id: ApiKeyId,
    val tenantId: TenantId,
    val name: String,
    val keyPrefix: String,
    val enabled: Boolean,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val expiresAt: Instant?,
    val createdBy: UserId?,
) {
    fun isExpired(): Boolean = expiresAt != null && Instant.now().isAfter(expiresAt)

    fun isUsable(): Boolean = enabled && !isExpired()
}

/**
 * Returned once when creating a new API key.
 * Contains the plaintext key that must be shown to the user immediately —
 * only the hash is stored in the database.
 */
data class ApiKeyWithSecret(
    val apiKey: ApiKey,
    val plaintextKey: String,
)
