package app.epistola.suite.feedback

import app.epistola.suite.common.ids.FeedbackKey
import app.epistola.suite.common.ids.TenantKey
import java.time.OffsetDateTime

data class FeedbackSummary(
    val id: FeedbackKey,
    val tenantKey: TenantKey,
    val title: String,
    val category: FeedbackCategory,
    val status: FeedbackStatus,
    val priority: FeedbackPriority,
    val sourceUrl: String?,
    val createdByName: String,
    val commentCount: Int,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)
