package app.epistola.suite.feedback.queries

import app.epistola.suite.common.ids.FeedbackId
import app.epistola.suite.common.ids.FeedbackKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class GetFeedbackByExternalRef(
    val tenantKey: TenantKey,
    val externalRef: String,
) : Query<FeedbackId?>

@Component
class GetFeedbackByExternalRefHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetFeedbackByExternalRef, FeedbackId?> {
    override fun handle(query: GetFeedbackByExternalRef): FeedbackId? = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            """
            SELECT id FROM feedback
            WHERE tenant_key = :tenantKey AND external_ref = :externalRef
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .bind("externalRef", query.externalRef)
            .mapTo(java.util.UUID::class.java)
            .findOne()
            .orElse(null)
            ?.let { FeedbackId(FeedbackKey(it), TenantId(query.tenantKey)) }
    }
}
