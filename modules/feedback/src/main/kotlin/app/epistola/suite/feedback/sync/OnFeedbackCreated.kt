package app.epistola.suite.feedback.sync

import app.epistola.suite.feedback.Feedback
import app.epistola.suite.feedback.SyncStatus
import app.epistola.suite.feedback.commands.CreateFeedback
import app.epistola.suite.feedback.commands.UpdateFeedbackSyncRef
import app.epistola.suite.feedback.queries.GetFeedbackSyncConfig
import app.epistola.suite.mediator.EventHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Reacts to feedback creation by syncing to the configured external issue tracker.
 *
 * Runs after the creating transaction commits (AFTER_COMMIT phase).
 * If sync fails, the feedback remains with sync_status=PENDING for the retry scheduler.
 */
@Component
class OnFeedbackCreated(
    private val feedbackSyncPort: FeedbackSyncPort,
) : EventHandler<CreateFeedback> {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun on(event: CreateFeedback, result: Any?) {
        val feedback = result as? Feedback ?: return

        if (feedback.syncStatus != SyncStatus.PENDING) {
            return
        }

        val config = GetFeedbackSyncConfig(event.id.tenantKey).query() ?: return

        try {
            val syncResult = feedbackSyncPort.createTicket(config, feedback, screenshot = null)

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
}
