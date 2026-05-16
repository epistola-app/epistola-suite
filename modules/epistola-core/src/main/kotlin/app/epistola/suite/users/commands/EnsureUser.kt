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
 * is a no-op when a row with that id already exists.
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
            // Target-less ON CONFLICT DO NOTHING. It must be:
            //  - atomic: callers may race (concurrent test classes, parallel
            //    logins) — a check-then-insert (WHERE NOT EXISTS) would TOCTOU
            //    and violate users_pkey.
            //  - tolerant of EITHER unique constraint: re-ensuring the same user
            //    duplicates BOTH the id PK and the (external_id, provider)
            //    unique index. `ON CONFLICT (id)` only suppresses its single
            //    arbiter, so PostgreSQL would still raise the other violation.
            // Target-less DO NOTHING ignores any unique conflict atomically.
            // Safe because callers key by a stable id with an external_id that
            // is unique per distinct user/principal, so the only conflict is a
            // genuine re-ensure of the same row.
            handle.createUpdate(
                """
                INSERT INTO users (id, external_id, email, display_name, provider, enabled, created_at)
                VALUES (:id, :externalId, :email, :displayName, :provider, true, NOW())
                ON CONFLICT DO NOTHING
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
