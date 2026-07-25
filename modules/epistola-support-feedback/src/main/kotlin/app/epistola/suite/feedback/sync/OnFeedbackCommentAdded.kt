// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.feedback.sync

import app.epistola.suite.common.ids.FeedbackId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.feedback.FeedbackComment
import app.epistola.suite.feedback.SyncStatus
import app.epistola.suite.feedback.commands.AddFeedbackComment
import app.epistola.suite.feedback.commands.UpdateFeedbackCommentExternalRef
import app.epistola.suite.feedback.queries.GetFeedback
import app.epistola.suite.mediator.EventHandler
import app.epistola.suite.mediator.EventPhase
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.HubConnectivityService
import app.epistola.suite.support.isHubUnreachable
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Reacts to local comment creation by syncing it to the external issue tracker.
 *
 * Only fires for [AddFeedbackComment] (local comments). Inbound comments via
 * [app.epistola.suite.feedback.commands.SyncFeedbackComment] do not trigger this,
 * avoiding a sync loop.
 *
 * After a successful sync, stores the external comment ID on the local record
 * so the inbound poll scheduler can dedup and skip it.
 */
@Component
class OnFeedbackCommentAdded(
    private val feedbackSyncPort: FeedbackSyncPort,
    private val connectivity: HubConnectivityService,
) : EventHandler<AddFeedbackComment> {

    private val log = LoggerFactory.getLogger(javaClass)

    override val phase = EventPhase.IMMEDIATE

    override fun on(event: AddFeedbackComment, result: Any?) {
        val comment = result as? FeedbackComment ?: run {
            log.warn("Expected FeedbackComment result from AddFeedbackComment but got {}", result?.javaClass?.name)
            return
        }

        val feedbackId = FeedbackId(event.id.feedbackKey, TenantId(event.id.tenantKey))
        val feedback = GetFeedback(feedbackId).query() ?: return

        if (feedback.syncStatus != SyncStatus.SYNCED || feedback.externalRef == null) {
            return
        }

        if (!feedbackSyncPort.isEnabled()) {
            return
        }

        // Not registered yet: leave external_comment_id null so the retry sweep pushes it later.
        if (!feedbackSyncPort.isReady()) {
            return
        }

        // Hub known unreachable: leave external_comment_id null so the retry sweep pushes it later.
        if (!connectivity.reachable()) {
            return
        }

        try {
            val ref = feedbackSyncPort.addComment(feedback, comment)

            UpdateFeedbackCommentExternalRef(
                id = event.id,
                externalCommentId = ref.externalCommentId,
            ).execute()

            log.info("Synced comment {} to external issue #{}", comment.id, feedback.externalRef)
        } catch (e: Exception) {
            if (e.isHubUnreachable()) {
                log.warn("Comment sync deferred for comment {} (hub unreachable): {}", comment.id, e.message)
            } else {
                log.error("Failed to sync comment {} to external issue #{}: {}", comment.id, feedback.externalRef, e.message, e)
            }
        }
    }
}
