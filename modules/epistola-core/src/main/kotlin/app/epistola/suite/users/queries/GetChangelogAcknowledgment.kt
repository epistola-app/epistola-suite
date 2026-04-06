package app.epistola.suite.users.queries

import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.SystemInternal
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class GetChangelogAcknowledgment(
    val userId: UserKey,
) : Query<String?>,
    SystemInternal

@Component
class GetChangelogAcknowledgmentHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetChangelogAcknowledgment, String?> {
    override fun handle(query: GetChangelogAcknowledgment): String? = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            """
            SELECT version
            FROM changelog_acknowledgments
            WHERE user_id = :userId
            """,
        )
            .bind("userId", query.userId.value)
            .mapTo(String::class.java)
            .findOne()
            .orElse(null)
    }
}
