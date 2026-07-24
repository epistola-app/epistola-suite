// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.apikeys

import app.epistola.suite.common.ids.ApiKeyKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.security.TenantRole
import app.epistola.suite.time.EpistolaClock
import java.sql.ResultSet
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
    /**
     * Least-privilege scope: the tenant roles this key authenticates as. The auth
     * filter grants exactly these for the key's tenant. Defaults to all roles only
     * as a convenience for in-code/test/demo construction — keys created through
     * [app.epistola.suite.apikeys.commands.CreateApiKey] always set an explicit subset.
     */
    val roles: Set<TenantRole> = TenantRole.entries.toSet(),
) {
    fun isExpired(now: Instant = EpistolaClock.instant()): Boolean = expiresAt != null && now.isAfter(expiresAt)

    fun isUsable(): Boolean = enabled && !isExpired()
}

/**
 * Maps a result-set row into an [ApiKey]. Shared by the listing and by-hash queries so the
 * column→field mapping (including the `roles` VARCHAR[] → `Set<TenantRole>` decode, which JDBI
 * cannot do reflectively) lives in one place. Pass [withDisplayName]=true when the query joins
 * the creator's `users` row as `created_by_display_name`.
 */
internal fun ResultSet.toApiKey(withDisplayName: Boolean): ApiKey = ApiKey(
    id = ApiKeyKey.of(getString("id")),
    tenantKey = TenantKey.of(getString("tenant_key")),
    name = getString("name"),
    keyPrefix = getString("key_prefix"),
    enabled = getBoolean("enabled"),
    createdAt = getObject("created_at", java.time.OffsetDateTime::class.java).toInstant(),
    lastUsedAt = getObject("last_used_at", java.time.OffsetDateTime::class.java)?.toInstant(),
    expiresAt = getObject("expires_at", java.time.OffsetDateTime::class.java)?.toInstant(),
    createdBy = getString("created_by")?.let { UserKey.of(it) },
    revokedAt = getObject("revoked_at", java.time.OffsetDateTime::class.java)?.toInstant(),
    revokedBy = getString("revoked_by")?.let { UserKey.of(it) },
    createdByDisplayName = if (withDisplayName) getString("created_by_display_name") else null,
    roles = rolesFromSqlArray(getArray("roles")),
)

private fun rolesFromSqlArray(array: java.sql.Array?): Set<TenantRole> = ((array?.array as? Array<*>)?.toList() ?: emptyList())
    .filterIsInstance<String>()
    .mapNotNull { runCatching { TenantRole.valueOf(it) }.getOrNull() }
    .toSet()

/**
 * Returned once when creating a new API key.
 * Contains the plaintext key that must be shown to the user immediately —
 * only the hash is stored in the database.
 */
data class ApiKeyWithSecret(
    val apiKey: ApiKey,
    val plaintextKey: String,
)
