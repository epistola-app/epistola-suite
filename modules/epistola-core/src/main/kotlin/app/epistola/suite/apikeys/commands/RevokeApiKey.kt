package app.epistola.suite.apikeys.commands

import app.epistola.suite.common.ids.ApiKeyKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

data class RevokeApiKey(
    val tenantId: TenantKey,
    val id: ApiKeyKey,
    val revokedBy: UserKey? = null,
) : Command<Boolean>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_USERS
    override val tenantKey get() = tenantId
}

@Component
class RevokeApiKeyHandler(
    private val jdbi: Jdbi,
) : CommandHandler<RevokeApiKey, Boolean> {

    override fun handle(command: RevokeApiKey): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createUpdate(
            """
            UPDATE api_keys
               SET enabled = false,
                   revoked_at = NOW(),
                   revoked_by = :revokedBy
             WHERE id = :id
               AND tenant_key = :tenantId
            """,
        )
            .bind("id", command.id)
            .bind("tenantId", command.tenantId)
            .bind("revokedBy", command.revokedBy?.value)
            .execute() > 0
    }
}
