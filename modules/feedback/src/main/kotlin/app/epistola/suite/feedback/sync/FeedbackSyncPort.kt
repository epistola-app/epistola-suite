package app.epistola.suite.feedback.sync

import app.epistola.suite.feedback.Feedback
import app.epistola.suite.feedback.FeedbackAssetContent
import app.epistola.suite.feedback.FeedbackComment
import app.epistola.suite.feedback.FeedbackStatus
import java.time.Instant

/**
 * Port for syncing feedback to an external system.
 *
 * The production implementation (`HubFeedbackSyncAdapter`, in the `epistola-support`
 * module) pushes feedback to epistola-hub over gRPC; [NoOpFeedbackSyncAdapter] is the
 * default when the support tier is disabled. Authentication and the target are
 * installation-wide — there is no per-tenant configuration — so the methods take only the
 * feedback/comment being synced. Each [Feedback] carries its own `tenantKey` and (once
 * synced) `externalRef`.
 */
interface FeedbackSyncPort {
    /** True when a real sync target is wired (i.e. not the no-op). Gates the drivers. */
    fun isEnabled(): Boolean

    fun createTicket(feedback: Feedback, assets: List<FeedbackAssetContent>): SyncResult

    /** [feedback] must already be synced ([Feedback.externalRef] non-null). */
    fun addComment(feedback: Feedback, comment: FeedbackComment): ExternalCommentRef

    /** [feedback] must already be synced ([Feedback.externalRef] non-null). */
    fun updateStatus(feedback: Feedback, status: FeedbackStatus)

    /**
     * Inbound updates (status changes, external comments) recorded after [since], across all
     * tenants of this installation. Each [ExternalUpdate] carries the tenant it belongs to.
     */
    fun fetchUpdates(since: Instant): List<ExternalUpdate>
}

data class SyncResult(
    val externalRef: String,
    val externalUrl: String,
)

data class ExternalCommentRef(
    val externalCommentId: String,
)
