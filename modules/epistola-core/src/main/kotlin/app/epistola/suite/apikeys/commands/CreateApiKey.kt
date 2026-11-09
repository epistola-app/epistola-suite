// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.apikeys.commands

import app.epistola.suite.apikeys.ApiKey
import app.epistola.suite.apikeys.ApiKeyService
import app.epistola.suite.apikeys.ApiKeyWithSecret
import app.epistola.suite.applications.commands.CreateApplication
import app.epistola.suite.applications.queries.GetApplication
import app.epistola.suite.common.ids.ApiKeyKey
import app.epistola.suite.common.ids.ApplicationKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.PrincipalApplication
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.TenantRole
import app.epistola.suite.time.EpistolaClock
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.ValidationException
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Create an API key: a credential of an application (ADR 0054).
 *
 * With [applicationId] the key acts with that application's roles, and [roles] must be left out.
 * Without one, the key gets an application of its own, named after it, with [roles] (all of them
 * when left out), that sees every document of the tenant: what every key was before applications,
 * and what the upgrade made of each existing key. The UI always names an application.
 */
data class CreateApiKey(
    val tenantId: TenantKey,
    val name: String,
    val roles: Set<TenantRole>? = null,
    val expiresAt: Instant? = null,
    val createdBy: UserKey? = null,
    val applicationId: ApplicationKey? = null,
) : Command<ApiKeyWithSecret>,
    RequiresPermission {
    override val permission get() = Permission.CREDENTIALS_MANAGE
    override val tenantKey get() = tenantId

    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.length <= MAX_NAME_LENGTH) { "Name must be $MAX_NAME_LENGTH characters or less" }
        validate("roles", roles == null || roles.isNotEmpty()) { "Select at least one role" }
        validate("roles", applicationId == null || roles == null) { "A key acts with its application's roles" }
    }
}

@Component
class CreateApiKeyHandler(
    private val jdbi: Jdbi,
    private val apiKeyService: ApiKeyService,
) : CommandHandler<CreateApiKey, ApiKeyWithSecret> {

    override fun handle(command: CreateApiKey): ApiKeyWithSecret {
        val application = if (command.applicationId != null) {
            GetApplication(command.tenantId, command.applicationId).query()
                ?: throw ValidationException("applicationId", "No such application in this tenant")
        } else {
            CreateApplication(
                tenantKey = command.tenantId,
                name = command.name,
                roles = command.roles ?: TenantRole.entries.toSet(),
                seesAllDocuments = true,
            ).execute()
        }

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
            application = PrincipalApplication(application.id, application.seesAllDocuments),
        )

        jdbi.useHandle<Exception> { handle ->
            // The roles are a copy of the application's, so a release before applications, which
            // reads them from the key, still grants exactly these after a rollback.
            handle.createUpdate(
                """
                INSERT INTO api_keys (id, tenant_key, name, key_hash, key_prefix, enabled,
                                      created_at, expires_at, created_by, roles, application_id)
                VALUES (:id, :tenantId, :name, :keyHash, :keyPrefix, :enabled,
                        :createdAt, :expiresAt, :createdBy, :roles::varchar[], :applicationId)
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
                .bind("applicationId", application.id)
                .execute()
        }

        return ApiKeyWithSecret(
            apiKey = apiKey,
            plaintextKey = plaintextKey,
        )
    }
}
