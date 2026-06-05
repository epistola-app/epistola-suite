package app.epistola.suite.feedback.sync

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.feedback.FeedbackStatus
import java.time.Instant

/**
 * Represents an update fetched from the external sync target via polling.
 *
 * Carries [tenantKey] because a single installation-wide poll returns updates across all
 * tenants; the poll scheduler uses it to resolve the local feedback item.
 */
sealed class ExternalUpdate {
    abstract val tenantKey: TenantKey
    abstract val externalRef: String
    abstract val occurredAt: Instant

    data class Comment(
        override val tenantKey: TenantKey,
        override val externalRef: String,
        override val occurredAt: Instant,
        val externalCommentId: String,
        val authorName: String,
        val authorEmail: String?,
        val body: String,
    ) : ExternalUpdate()

    data class StatusChange(
        override val tenantKey: TenantKey,
        override val externalRef: String,
        override val occurredAt: Instant,
        val newStatus: FeedbackStatus,
    ) : ExternalUpdate()
}
