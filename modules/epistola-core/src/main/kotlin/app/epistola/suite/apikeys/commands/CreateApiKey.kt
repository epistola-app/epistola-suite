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
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.Instant

data class CreateApiKey(
    val tenantId: TenantKey,
    val name: String,
    val expiresAt: Instant? = null,
    val createdBy: UserKey? = null,
) : Command<ApiKeyWithSecret>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_USERS
    override val tenantKey get() = tenantId

    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.length <= 100) { "Name must be 100 characters or less" }
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
            createdAt = Instant.now(),
            lastUsedAt = null,
            expiresAt = command.expiresAt,
            createdBy = command.createdBy,
        )

        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO api_keys (id, tenant_key, name, key_hash, key_prefix, enabled,
                                      created_at, expires_at, created_by)
                VALUES (:id, :tenantId, :name, :keyHash, :keyPrefix, :enabled,
                        :createdAt, :expiresAt, :createdBy)
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
                .execute()
        }

        return ApiKeyWithSecret(
            apiKey = apiKey,
            plaintextKey = plaintextKey,
        )
    }
}
