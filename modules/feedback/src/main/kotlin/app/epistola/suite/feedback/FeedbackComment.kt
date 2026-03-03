package app.epistola.suite.feedback

import app.epistola.suite.common.ids.FeedbackCommentKey
import app.epistola.suite.common.ids.FeedbackKey
import app.epistola.suite.common.ids.TenantKey
import java.time.OffsetDateTime

data class FeedbackComment(
    val id: FeedbackCommentKey,
    val feedbackId: FeedbackKey,
    val tenantKey: TenantKey,
    val body: String,
    val authorName: String,
    val authorEmail: String?,
    val source: CommentSource,
    val externalCommentId: Long?,
    val createdAt: OffsetDateTime,
)

enum class CommentSource {
    LOCAL,
    GITHUB,
}
