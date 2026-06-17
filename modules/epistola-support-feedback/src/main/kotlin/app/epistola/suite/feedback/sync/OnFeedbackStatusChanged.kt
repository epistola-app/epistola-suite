package app.epistola.suite.feedback.sync

import app.epistola.suite.feedback.SyncStatus
import app.epistola.suite.feedback.commands.UpdateFeedbackStatus
import app.epistola.suite.feedback.queries.GetFeedback
import app.epistola.suite.mediator.EventHandler
import app.epistola.suite.mediator.EventPhase
import app.epistola.suite.mediator.query
import app.epistola.suite.support.HubConnectivityService
import app.epistola.suite.support.isHubUnreachable
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Reacts to a local status change ([UpdateFeedbackStatus], raised by the UI) by pushing it to
 * the external sync target so operators on the hub see it.
 *
 * Only fires for [UpdateFeedbackStatus]. Inbound status changes from the poll use
 * [app.epistola.suite.feedback.commands.SyncFeedbackStatus] (no event handler), so applying a
 * polled-back status does not bounce straight back to the hub.
 *
 * Best-effort: only syncs feedback already mirrored to the hub
 * ([Feedback.externalRef] non-null, [SyncStatus.SYNCED]); a push failure is logged and left
 * for a later status change to reconcile (the hub keeps the operator-authoritative value).
 */
@Component
class OnFeedbackStatusChanged(
    private val feedbackSyncPort: FeedbackSyncPort,
    private val connectivity: HubConnectivityService,
) : EventHandler<UpdateFeedbackStatus> {

    private val log = LoggerFactory.getLogger(javaClass)

    override val phase = EventPhase.IMMEDIATE

    override fun on(event: UpdateFeedbackStatus, result: Any?) {
        if (result != true) {
            // No row changed (status unchanged or feedback missing); nothing to sync.
            return
        }

        if (!feedbackSyncPort.isEnabled()) {
            return
        }

        // Not registered yet: skip the push; the hub stays the authoritative source for status.
        if (!feedbackSyncPort.isReady()) {
            return
        }

        // Hub known unreachable: skip the push; a later status change reconciles when it is back up.
        if (!connectivity.reachable()) {
            return
        }

        val feedback = GetFeedback(event.id).query() ?: return
        if (feedback.syncStatus != SyncStatus.SYNCED || feedback.externalRef == null) {
            return
        }

        try {
            feedbackSyncPort.updateStatus(feedback, feedback.status)
            log.info("Synced status {} for feedback {} to external issue {}", feedback.status, feedback.id, feedback.externalRef)
        } catch (e: Exception) {
            if (e.isHubUnreachable()) {
                log.warn("Status sync deferred for feedback {} (hub unreachable): {}", feedback.id, e.message)
            } else {
                log.error("Failed to sync status for feedback {} to {}: {}", feedback.id, feedback.externalRef, e.message, e)
            }
        }
    }
}
