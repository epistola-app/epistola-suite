package app.epistola.suite.support.feedback

import app.epistola.hub.client.EpistolaHubClient
import app.epistola.hub.proto.v1.AddFeedbackCommentRequest
import app.epistola.hub.proto.v1.FeedbackUpdate
import app.epistola.hub.proto.v1.FetchFeedbackUpdatesRequest
import app.epistola.hub.proto.v1.SubmitFeedbackRequest
import app.epistola.hub.proto.v1.UpdateFeedbackStatusRequest
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.feedback.Feedback
import app.epistola.suite.feedback.FeedbackAssetContent
import app.epistola.suite.feedback.FeedbackCategory
import app.epistola.suite.feedback.FeedbackComment
import app.epistola.suite.feedback.FeedbackPriority
import app.epistola.suite.feedback.FeedbackStatus
import app.epistola.suite.feedback.sync.ExternalCommentRef
import app.epistola.suite.feedback.sync.ExternalUpdate
import app.epistola.suite.feedback.sync.FeedbackSyncPort
import app.epistola.suite.feedback.sync.SyncResult
import com.google.protobuf.ByteString
import com.google.protobuf.Timestamp
import org.slf4j.LoggerFactory
import java.time.Instant
import app.epistola.hub.proto.v1.FeedbackCategory as ProtoCategory
import app.epistola.hub.proto.v1.FeedbackPriority as ProtoPriority
import app.epistola.hub.proto.v1.FeedbackStatus as ProtoStatus

/**
 * Production [FeedbackSyncPort] that pushes feedback to epistola-hub over gRPC and polls it
 * back. Authentication is installation-wide: [EpistolaHubClient] attaches the registered
 * API key to every call. Wired only when `epistola.support.enabled=true`
 * (see [SupportConfiguration]); otherwise the no-op adapter from the feedback module is used.
 *
 * Methods let hub errors propagate so the feedback retry scheduler can re-attempt failed
 * pushes (e.g. when registration hasn't completed yet).
 */
class HubFeedbackSyncAdapter(
    private val client: EpistolaHubClient,
) : FeedbackSyncPort {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun isEnabled(): Boolean = true

    override fun createTicket(feedback: Feedback, assets: List<FeedbackAssetContent>): SyncResult {
        val request =
            SubmitFeedbackRequest
                .newBuilder()
                .setTenant(feedback.tenantKey.value)
                .setSuiteFeedbackId(feedback.id.value.toString())
                .setTitle(feedback.title)
                .setDescription(feedback.description)
                .setCategory(feedback.category.toProto())
                .setPriority(feedback.priority.toProto())
                .setStatus(feedback.status.toProto())
                .apply {
                    feedback.sourceUrl?.let { sourceUrl = it }
                    feedback.consoleLogs?.let { consoleLogs = it }
                    feedback.metadata?.let { metadata = it }
                }.addAllAssets(assets.map { it.toProto() })
                .build()

        val response = client.submitFeedback(request)
        log.info("Submitted feedback {} to hub as {}", feedback.id, response.feedbackId)
        return SyncResult(externalRef = response.feedbackId, externalUrl = response.url)
    }

    override fun addComment(feedback: Feedback, comment: FeedbackComment): ExternalCommentRef {
        val externalRef = requireNotNull(feedback.externalRef) { "Feedback ${feedback.id} has no external ref" }
        val request =
            AddFeedbackCommentRequest
                .newBuilder()
                .setFeedbackId(externalRef)
                .setAuthorName(comment.authorName)
                .setBody(comment.body)
                .apply { comment.authorEmail?.let { authorEmail = it } }
                .build()

        val response = client.addFeedbackComment(request)
        return ExternalCommentRef(externalCommentId = response.commentId)
    }

    override fun updateStatus(feedback: Feedback, status: FeedbackStatus) {
        val externalRef = requireNotNull(feedback.externalRef) { "Feedback ${feedback.id} has no external ref" }
        client.updateFeedbackStatus(
            UpdateFeedbackStatusRequest
                .newBuilder()
                .setFeedbackId(externalRef)
                .setStatus(status.toProto())
                .build(),
        )
    }

    override fun fetchUpdates(since: Instant): List<ExternalUpdate> {
        val response =
            client.fetchFeedbackUpdates(
                FetchFeedbackUpdatesRequest.newBuilder().setSince(since.toTimestamp()).build(),
            )
        return response.updatesList.mapNotNull { it.toExternalUpdate() }
    }
}

private fun FeedbackAssetContent.toProto(): app.epistola.hub.proto.v1.FeedbackAsset = app.epistola.hub.proto.v1.FeedbackAsset
    .newBuilder()
    .setContentType(contentType)
    .apply { filename?.let { filename = it } }
    .setContent(ByteString.copyFrom(content))
    .build()

private fun FeedbackUpdate.toExternalUpdate(): ExternalUpdate? {
    val tenantKey = TenantKey(tenant)
    val occurredAt = occurredAt.toInstant()
    return when (updateCase) {
        FeedbackUpdate.UpdateCase.STATUS_CHANGE ->
            ExternalUpdate.StatusChange(
                tenantKey = tenantKey,
                externalRef = feedbackId,
                occurredAt = occurredAt,
                newStatus = statusChange.status.toFeedbackStatus(),
            )

        FeedbackUpdate.UpdateCase.COMMENT ->
            ExternalUpdate.Comment(
                tenantKey = tenantKey,
                externalRef = feedbackId,
                occurredAt = occurredAt,
                externalCommentId = comment.externalCommentId,
                authorName = comment.authorName,
                authorEmail = comment.authorEmail.ifBlank { null },
                body = comment.body,
            )

        FeedbackUpdate.UpdateCase.UPDATE_NOT_SET, null -> null
    }
}

private fun FeedbackStatus.toProto(): ProtoStatus = when (this) {
    FeedbackStatus.OPEN -> ProtoStatus.FEEDBACK_STATUS_OPEN
    FeedbackStatus.IN_PROGRESS -> ProtoStatus.FEEDBACK_STATUS_IN_PROGRESS
    FeedbackStatus.RESOLVED -> ProtoStatus.FEEDBACK_STATUS_RESOLVED
    FeedbackStatus.CLOSED -> ProtoStatus.FEEDBACK_STATUS_CLOSED
}

private fun ProtoStatus.toFeedbackStatus(): FeedbackStatus = when (this) {
    ProtoStatus.FEEDBACK_STATUS_OPEN -> FeedbackStatus.OPEN
    ProtoStatus.FEEDBACK_STATUS_IN_PROGRESS -> FeedbackStatus.IN_PROGRESS
    ProtoStatus.FEEDBACK_STATUS_RESOLVED -> FeedbackStatus.RESOLVED
    ProtoStatus.FEEDBACK_STATUS_CLOSED -> FeedbackStatus.CLOSED
    ProtoStatus.FEEDBACK_STATUS_UNSPECIFIED, ProtoStatus.UNRECOGNIZED -> FeedbackStatus.OPEN
}

private fun FeedbackCategory.toProto(): ProtoCategory = when (this) {
    FeedbackCategory.BUG -> ProtoCategory.FEEDBACK_CATEGORY_BUG
    FeedbackCategory.FEATURE_REQUEST -> ProtoCategory.FEEDBACK_CATEGORY_FEATURE_REQUEST
    FeedbackCategory.QUESTION -> ProtoCategory.FEEDBACK_CATEGORY_QUESTION
    FeedbackCategory.OTHER -> ProtoCategory.FEEDBACK_CATEGORY_OTHER
}

private fun FeedbackPriority.toProto(): ProtoPriority = when (this) {
    FeedbackPriority.LOW -> ProtoPriority.FEEDBACK_PRIORITY_LOW
    FeedbackPriority.MEDIUM -> ProtoPriority.FEEDBACK_PRIORITY_MEDIUM
    FeedbackPriority.HIGH -> ProtoPriority.FEEDBACK_PRIORITY_HIGH
    FeedbackPriority.CRITICAL -> ProtoPriority.FEEDBACK_PRIORITY_CRITICAL
}

private fun Instant.toTimestamp(): Timestamp = Timestamp
    .newBuilder()
    .setSeconds(epochSecond)
    .setNanos(nano)
    .build()

private fun Timestamp.toInstant(): Instant = Instant.ofEpochSecond(seconds, nanos.toLong())
