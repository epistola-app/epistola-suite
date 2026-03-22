package app.epistola.suite.apikeys.commands

import app.epistola.suite.apikeys.ApiKeyRepository
import app.epistola.suite.common.ids.ApiKeyKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.springframework.stereotype.Component

data class RevokeApiKey(
    val tenantId: TenantKey,
    val id: ApiKeyKey,
) : Command<Boolean>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_USERS
    override val tenantKey get() = tenantId
}

@Component
class RevokeApiKeyHandler(
    private val apiKeyRepository: ApiKeyRepository,
) : CommandHandler<RevokeApiKey, Boolean> {

    override fun handle(command: RevokeApiKey): Boolean = apiKeyRepository.disable(command.id)
}
