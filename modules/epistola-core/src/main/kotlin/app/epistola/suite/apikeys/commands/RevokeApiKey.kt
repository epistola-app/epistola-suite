package app.epistola.suite.apikeys.commands

import app.epistola.suite.apikeys.ApiKeyRepository
import app.epistola.suite.common.ids.ApiKeyKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import org.springframework.stereotype.Component

data class RevokeApiKey(
    val id: ApiKeyKey,
) : Command<Boolean>

@Component
class RevokeApiKeyHandler(
    private val apiKeyRepository: ApiKeyRepository,
) : CommandHandler<RevokeApiKey, Boolean> {

    override fun handle(command: RevokeApiKey): Boolean = apiKeyRepository.disable(command.id)
}
