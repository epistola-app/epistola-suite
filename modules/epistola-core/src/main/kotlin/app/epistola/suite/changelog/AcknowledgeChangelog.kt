package app.epistola.suite.changelog

import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.SystemInternal
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class AcknowledgeChangelog(
    val userId: UserKey,
    val version: String,
) : Command<Unit>,
    SystemInternal

@Component
class AcknowledgeChangelogHandler(
    private val jdbi: Jdbi,
) : CommandHandler<AcknowledgeChangelog, Unit> {
    override fun handle(command: AcknowledgeChangelog) {
        jdbi.withHandleUnchecked { handle ->
            handle.createUpdate(
                """
                INSERT INTO changelog_acknowledgments (user_id, version, acknowledged_at)
                VALUES (:userId, :version, NOW())
                ON CONFLICT (user_id)
                DO UPDATE SET version = :version, acknowledged_at = NOW()
                """,
            )
                .bind("userId", command.userId.value)
                .bind("version", command.version)
                .execute()
        }
    }
}
