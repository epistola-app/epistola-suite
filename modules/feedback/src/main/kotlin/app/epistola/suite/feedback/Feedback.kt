package app.epistola.suite.feedback

import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.FeedbackKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import java.time.OffsetDateTime

data class Feedback(
    val id: FeedbackKey,
    val tenantKey: TenantKey,
    val title: String,
    val description: String,
    val category: FeedbackCategory,
    val status: FeedbackStatus,
    val priority: FeedbackPriority,
    val sourceUrl: String?,
    val screenshotKey: AssetKey?,
    val consoleLogs: String?,
    val metadata: String?,
    val createdBy: UserKey,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val externalRef: String?,
    val externalUrl: String?,
    val syncStatus: SyncStatus,
)
