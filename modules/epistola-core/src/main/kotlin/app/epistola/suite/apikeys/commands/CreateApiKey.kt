// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.apikeys.commands

import app.epistola.suite.apikeys.ApiKey
import app.epistola.suite.apikeys.ApiKeyService
import app.epistola.suite.apikeys.ApiKeyWithSecret
import app.epistola.suite.applications.queries.GetApplication
import app.epistola.suite.common.ids.ApiKeyKey
import app.epistola.suite.common.ids.ApplicationKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.PrincipalApplication
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.time.EpistolaClock
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.ValidationException
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Create an API key: a credential of [applicationId] (ADR 0054). The key has no roles of its own; it
 * acts with its application's, and sees what the application sees. Rotating means creating the new
 * key on the same application and revoking the old one, so who owns the documents does not change.
 */
data class CreateApiKey(
    val tenantId: TenantKey,
    val name: String,
    val applicationId: ApplicationKey,
    val expiresAt: Instant? = null,
    val createdBy: UserKey? = null,
) : Command<ApiKeyWithSecret>,
    RequiresPermission {
    override val permission get() = Permission.CREDENTIALS_MANAGE
    override val tenantKey get() = tenantId

    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.length <= MAX_NAME_LENGTH) { "Name must be $MAX_NAME_LENGTH characters or less" }
    }
}

@Component
class CreateApiKeyHandler(
    private val jdbi: Jdbi,
    private val apiKeyService: ApiKeyService,
) : CommandHandler<CreateApiKey, ApiKeyWithSecret> {

    override fun handle(command: CreateApiKey): ApiKeyWithSecret {
        val application = GetApplication(command.tenantId, command.applicationId).query()
            ?: throw ValidationException("applicationId", "No such application in this tenant")

        // A key may carry only roles its creator holds in the tenant. Roles do not inherit, and
        // creating keys is an administrator's permission, so without this an administrator who
        // does not publish could mint a key for an application that does.
        val ungrantable = application.roles - SecurityContext.current().rolesFor(command.tenantId)
        validate("roles", ungrantable.isEmpty()) {
            "You can only grant roles you hold in this tenant: ${ungrantable.sortedBy { it.ordinal }.joinToString { it.name }}"
        }

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
            roles = application.roles,
            application = PrincipalApplication(application.id, application.seesAllDocuments, application.ownsUnattributedDocuments),
        )

        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO api_keys (id, tenant_key, name, key_hash, key_prefix, enabled,
                                      created_at, expires_at, created_by, application_id)
                VALUES (:id, :tenantId, :name, :keyHash, :keyPrefix, :enabled,
                        :createdAt, :expiresAt, :createdBy, :applicationId)
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
                .bind("applicationId", application.id)
                .execute()
        }

        return ApiKeyWithSecret(
            apiKey = apiKey,
            plaintextKey = plaintextKey,
        )
    }
}
