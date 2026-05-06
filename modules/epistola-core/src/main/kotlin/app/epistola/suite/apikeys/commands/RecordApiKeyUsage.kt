package app.epistola.suite.apikeys.commands

import app.epistola.suite.common.ids.ApiKeyKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.SystemInternal
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Updates an API key's `last_used_at` timestamp to NOW(). Best-effort —
 * intended for fire-and-forget use from [ApiKeyAuthenticationFilter] each
 * time a request authenticates with this key.
 *
 * `SystemInternal` because the auth filter dispatches it before user
 * authorization is established for the request.
 */
data class RecordApiKeyUsage(
    val id: ApiKeyKey,
) : Command<Unit>,
    SystemInternal

@Component
class RecordApiKeyUsageHandler(
    private val jdbi: Jdbi,
) : CommandHandler<RecordApiKeyUsage, Unit> {
    override fun handle(command: RecordApiKeyUsage) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE api_keys SET last_used_at = NOW() WHERE id = :id
                """,
            )
                .bind("id", command.id)
                .execute()
        }
    }
}
