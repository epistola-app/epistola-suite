package app.epistola.suite.feedback.queries

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.feedback.FeedbackSyncConfig
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class GetFeedbackSyncConfig(
    val tenantKey: TenantKey,
) : Query<FeedbackSyncConfig?>

@Component
class GetFeedbackSyncConfigHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetFeedbackSyncConfig, FeedbackSyncConfig?> {
    override fun handle(query: GetFeedbackSyncConfig): FeedbackSyncConfig? = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            """
            SELECT * FROM feedback_sync_config
            WHERE tenant_key = :tenantKey
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .mapTo(FeedbackSyncConfig::class.java)
            .findOne()
            .orElse(null)
    }
}
