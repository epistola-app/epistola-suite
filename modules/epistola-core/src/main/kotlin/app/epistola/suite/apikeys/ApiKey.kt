package app.epistola.suite.apikeys

import app.epistola.suite.common.ids.ApiKeyKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.time.EpistolaClock
import java.time.Instant

/**
 * Domain model for an API key.
 *
 * API keys provide authentication for external systems accessing the REST API.
 * Each key belongs to a single tenant and acts as a Non-Personal Account (NPA)
 * at runtime — the key's own ID is used as the userId in EpistolaPrincipal.
 */
data class ApiKey(
    val id: ApiKeyKey,
    val tenantKey: TenantKey,
    val name: String,
    val keyPrefix: String,
    val enabled: Boolean,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val expiresAt: Instant?,
    val createdBy: UserKey?,
    val revokedAt: Instant? = null,
    val revokedBy: UserKey? = null,
    /**
     * Display name of the user who created this key, resolved via JOIN on listing.
     * Null when the user no longer exists, when fetched outside a listing context
     * (e.g. by hash for authentication), or when [createdBy] itself is null.
     */
    val createdByDisplayName: String? = null,
) {
    fun isExpired(now: Instant = EpistolaClock.instant()): Boolean = expiresAt != null && now.isAfter(expiresAt)

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
