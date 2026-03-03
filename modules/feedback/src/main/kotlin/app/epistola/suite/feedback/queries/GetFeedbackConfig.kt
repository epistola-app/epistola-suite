package app.epistola.suite.feedback.queries

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.feedback.FeedbackConfig
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class GetFeedbackConfig(
    val tenantKey: TenantKey,
) : Query<FeedbackConfig?>

@Component
class GetFeedbackConfigHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetFeedbackConfig, FeedbackConfig?> {
    override fun handle(query: GetFeedbackConfig): FeedbackConfig? = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            """
            SELECT * FROM feedback_config
            WHERE tenant_key = :tenantKey
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .mapTo(FeedbackConfig::class.java)
            .findOne()
            .orElse(null)
    }
}
