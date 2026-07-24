// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.apikeys.commands

import app.epistola.suite.apikeys.ApiKey
import app.epistola.suite.apikeys.ApiKeyService
import app.epistola.suite.apikeys.ApiKeyWithSecret
import app.epistola.suite.common.ids.ApiKeyKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.TenantRole
import app.epistola.suite.time.EpistolaClock
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.Instant

data class CreateApiKey(
    val tenantId: TenantKey,
    val name: String,
    /**
     * Least-privilege scope: the tenant roles this key authenticates as. Must be non-empty.
     * Defaults to all roles only as a back-compat convenience for tests/fixtures — the UI
     * (`ApiKeyHandler`) always passes an explicit, user-chosen subset.
     */
    val roles: Set<TenantRole> = TenantRole.entries.toSet(),
    val expiresAt: Instant? = null,
    val createdBy: UserKey? = null,
) : Command<ApiKeyWithSecret>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_USERS
    override val tenantKey get() = tenantId

    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.length <= MAX_NAME_LENGTH) { "Name must be $MAX_NAME_LENGTH characters or less" }
        validate("roles", roles.isNotEmpty()) { "Select at least one role" }
    }
}

@Component
class CreateApiKeyHandler(
    private val jdbi: Jdbi,
    private val apiKeyService: ApiKeyService,
) : CommandHandler<CreateApiKey, ApiKeyWithSecret> {

    override fun handle(command: CreateApiKey): ApiKeyWithSecret {
        val plaintextKey = apiKeyService.generateKey()
        val keyHash = apiKeyService.hashKey(plaintextKey)
        val keyPrefix = apiKeyService.extractPrefix(plaintextKey)

        val apiKey = ApiKey(
            id = ApiKeyKey.generate(),
            tenantKey = command.tenantId,
            name = command.name,
            keyPrefix = keyPrefix,
            enabled = true,
            createdAt = EpistolaClock.instant(),
            lastUsedAt = null,
            expiresAt = command.expiresAt,
            createdBy = command.createdBy,
            roles = command.roles,
        )

        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO api_keys (id, tenant_key, name, key_hash, key_prefix, enabled,
                                      created_at, expires_at, created_by, roles)
                VALUES (:id, :tenantId, :name, :keyHash, :keyPrefix, :enabled,
                        :createdAt, :expiresAt, :createdBy, :roles::varchar[])
                """,
            )
                .bind("id", apiKey.id)
                .bind("tenantId", apiKey.tenantKey)
                .bind("name", apiKey.name)
                .bind("keyHash", keyHash)
                .bind("keyPrefix", apiKey.keyPrefix)
                .bind("enabled", apiKey.enabled)
                .bind("createdAt", apiKey.createdAt)
                .bind("expiresAt", apiKey.expiresAt)
                .bind("createdBy", apiKey.createdBy?.value)
                .bind("roles", apiKey.roles.map { it.name }.toTypedArray())
                .execute()
        }

        return ApiKeyWithSecret(
            apiKey = apiKey,
            plaintextKey = plaintextKey,
        )
    }
}
