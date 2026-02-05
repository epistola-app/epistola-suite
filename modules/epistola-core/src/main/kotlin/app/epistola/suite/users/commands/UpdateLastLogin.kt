package app.epistola.suite.users.commands

import app.epistola.suite.common.ids.UserId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

/**
 * Command to update the last login timestamp for a user.
 */
data class UpdateLastLogin(
    val userId: UserId,
) : Command<Unit>

@Component
class UpdateLastLoginHandler(
    private val jdbi: Jdbi,
) : CommandHandler<UpdateLastLogin, Unit> {
    override fun handle(command: UpdateLastLogin) {
        jdbi.withHandleUnchecked { handle ->
            handle.createUpdate(
                """
                UPDATE users
                SET last_login_at = NOW()
                WHERE id = :userId
                """,
            )
                .bind("userId", command.userId.value)
                .execute()
        }
    }
}
