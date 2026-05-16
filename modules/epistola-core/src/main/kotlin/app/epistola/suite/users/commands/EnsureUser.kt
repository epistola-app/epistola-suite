package app.epistola.suite.users.commands

import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.users.AuthProvider
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

/**
 * Idempotently ensures a `users` row exists for a known, stable user id.
 *
 * Unlike [CreateUser] (which mints a fresh random id during OAuth2
 * auto-provisioning), this is keyed by a caller-supplied deterministic id and
 * is a no-op when the row already exists (`ON CONFLICT (id) DO NOTHING`).
 *
 * Used by authentication paths whose principal id is derived deterministically
 * rather than provisioned (e.g. local/config users), so that the audit foreign
 * keys (`created_by` / `updated_by` -> `users(id)`) are always satisfiable.
 */
data class EnsureUser(
    val id: UserKey,
    val externalId: String,
    val email: String,
    val displayName: String,
    val provider: AuthProvider,
) : Command<Unit>,
    SystemInternal

@Component
class EnsureUserHandler(
    private val jdbi: Jdbi,
) : CommandHandler<EnsureUser, Unit> {
    override fun handle(command: EnsureUser) {
        jdbi.withHandleUnchecked { handle ->
            handle.createUpdate(
                """
                INSERT INTO users (id, external_id, email, display_name, provider, enabled, created_at)
                VALUES (:id, :externalId, :email, :displayName, :provider, true, NOW())
                ON CONFLICT (id) DO NOTHING
                """,
            )
                .bind("id", command.id.value)
                .bind("externalId", command.externalId)
                .bind("email", command.email)
                .bind("displayName", command.displayName)
                .bind("provider", command.provider.name)
                .execute()
        }
    }
}
