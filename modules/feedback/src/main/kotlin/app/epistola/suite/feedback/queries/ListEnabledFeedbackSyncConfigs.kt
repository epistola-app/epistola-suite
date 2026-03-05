package app.epistola.suite.feedback.queries

import app.epistola.suite.feedback.FeedbackSyncConfig
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data object ListEnabledFeedbackSyncConfigs : Query<List<FeedbackSyncConfig>>

@Component
class ListEnabledFeedbackSyncConfigsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListEnabledFeedbackSyncConfigs, List<FeedbackSyncConfig>> {
    override fun handle(query: ListEnabledFeedbackSyncConfigs): List<FeedbackSyncConfig> =
        jdbi.withHandleUnchecked { handle ->
            handle.createQuery(
                """
                SELECT * FROM feedback_sync_config
                WHERE enabled = true
                """,
            )
                .mapTo(FeedbackSyncConfig::class.java)
                .list()
        }
}
