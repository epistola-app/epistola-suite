package app.epistola.suite.apikeys.commands

import app.epistola.suite.apikeys.ApiKey
import app.epistola.suite.apikeys.ApiKeyRepository
import app.epistola.suite.apikeys.ApiKeyService
import app.epistola.suite.apikeys.ApiKeyWithSecret
import app.epistola.suite.common.ids.ApiKeyKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.validation.validate
import org.springframework.stereotype.Component
import java.time.Instant

data class CreateApiKey(
    val tenantId: TenantKey,
    val name: String,
    val expiresAt: Instant? = null,
    val createdBy: UserKey? = null,
) : Command<ApiKeyWithSecret> {
    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.length <= 100) { "Name must be 100 characters or less" }
    }
}

@Component
class CreateApiKeyHandler(
    private val apiKeyRepository: ApiKeyRepository,
    private val apiKeyService: ApiKeyService,
) : CommandHandler<CreateApiKey, ApiKeyWithSecret> {

    override fun handle(command: CreateApiKey): ApiKeyWithSecret {
        val plaintextKey = apiKeyService.generateKey()
        val keyHash = apiKeyService.hashKey(plaintextKey)
        val keyPrefix = apiKeyService.extractPrefix(plaintextKey)

        val apiKey = ApiKey(
            id = ApiKeyKey.generate(),
            tenantId = command.tenantId,
            name = command.name,
            keyPrefix = keyPrefix,
            enabled = true,
            createdAt = Instant.now(),
            lastUsedAt = null,
            expiresAt = command.expiresAt,
            createdBy = command.createdBy,
        )

        apiKeyRepository.insert(apiKey, keyHash)

        return ApiKeyWithSecret(
            apiKey = apiKey,
            plaintextKey = plaintextKey,
        )
    }
}
