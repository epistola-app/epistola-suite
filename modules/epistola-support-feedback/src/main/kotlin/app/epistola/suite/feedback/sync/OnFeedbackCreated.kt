package app.epistola.suite.feedback.sync

import app.epistola.suite.common.ids.FeedbackAssetId
import app.epistola.suite.common.ids.FeedbackId
import app.epistola.suite.feedback.Feedback
import app.epistola.suite.feedback.FeedbackAssetContent
import app.epistola.suite.feedback.SyncStatus
import app.epistola.suite.feedback.commands.CreateFeedback
import app.epistola.suite.feedback.commands.UpdateFeedbackSyncRef
import app.epistola.suite.feedback.queries.GetFeedbackAssetContent
import app.epistola.suite.feedback.queries.ListFeedbackAssets
import app.epistola.suite.mediator.EventHandler
import app.epistola.suite.mediator.EventPhase
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.feedback.ConditionalOnSupportFeedbackModule
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Reacts to feedback creation by syncing to the external sync target.
 *
 * Runs in the IMMEDIATE phase (same call stack as the command).
 * If sync fails, the feedback remains with sync_status=PENDING for the retry scheduler.
 */
@Component
@ConditionalOnSupportFeedbackModule
class OnFeedbackCreated(
    private val feedbackSyncPort: FeedbackSyncPort,
) : EventHandler<CreateFeedback> {

    private val log = LoggerFactory.getLogger(javaClass)

    override val phase = EventPhase.IMMEDIATE

    override fun on(event: CreateFeedback, result: Any?) {
        val feedback = result as? Feedback ?: run {
            log.warn("Expected Feedback result from CreateFeedback but got {}", result?.javaClass?.name)
            return
        }

        if (feedback.syncStatus != SyncStatus.PENDING) {
            return
        }

        if (!feedbackSyncPort.isEnabled()) {
            return
        }

        // Not registered yet: leave it PENDING and let the retry sweep push it once ready.
        if (!feedbackSyncPort.isReady()) {
            return
        }

        try {
            val assets = loadAssetContents(event.id)
            val syncResult = feedbackSyncPort.createTicket(feedback, assets)

            UpdateFeedbackSyncRef(
                id = event.id,
                externalRef = syncResult.externalRef,
                externalUrl = syncResult.externalUrl,
            ).execute()

            log.info("Synced feedback {} to external ticket {}", feedback.id, syncResult.externalRef)
        } catch (e: Exception) {
            log.error("Failed to sync feedback {} to external issue tracker: {}", feedback.id, e.message, e)
        }
    }

    private fun loadAssetContents(feedbackId: FeedbackId): List<FeedbackAssetContent> {
        val assets = ListFeedbackAssets(feedbackId).query()
        return assets.mapNotNull { asset ->
            val assetId = FeedbackAssetId(asset.id, feedbackId)
            GetFeedbackAssetContent(assetId).query()
        }
    }
}
