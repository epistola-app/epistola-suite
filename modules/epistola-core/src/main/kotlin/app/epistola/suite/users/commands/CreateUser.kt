package app.epistola.suite.users.commands

import app.epistola.suite.common.ids.UserId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.users.AuthProvider
import app.epistola.suite.users.User
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Command to create a new user.
 *
 * Used for auto-provisioning users during OAuth2 login.
 */
data class CreateUser(
    val externalId: String,
    val email: String,
    val displayName: String,
    val provider: AuthProvider,
) : Command<User>

@Component
class CreateUserHandler(
    private val jdbi: Jdbi,
) : CommandHandler<CreateUser, User> {
    override fun handle(command: CreateUser): User {
        val userId = UserId.generate()

        jdbi.withHandleUnchecked { handle ->
            handle.createUpdate(
                """
                INSERT INTO users (id, external_id, email, display_name, provider, enabled, created_at)
                VALUES (:id, :externalId, :email, :displayName, :provider, true, NOW())
                """,
            )
                .bind("id", userId.value)
                .bind("externalId", command.externalId)
                .bind("email", command.email)
                .bind("displayName", command.displayName)
                .bind("provider", command.provider.name)
                .execute()
        }

        return User(
            id = userId,
            externalId = command.externalId,
            email = command.email,
            displayName = command.displayName,
            provider = command.provider,
            tenantMemberships = emptySet(),
            enabled = true,
            createdAt = OffsetDateTime.now(),
            lastLoginAt = null,
        )
    }
}
