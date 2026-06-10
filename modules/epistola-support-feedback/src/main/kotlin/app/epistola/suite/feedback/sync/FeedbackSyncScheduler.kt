package app.epistola.suite.feedback.sync

import app.epistola.suite.common.ids.FeedbackAssetId
import app.epistola.suite.common.ids.FeedbackCommentId
import app.epistola.suite.common.ids.FeedbackId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.feedback.Feedback
import app.epistola.suite.feedback.FeedbackAssetContent
import app.epistola.suite.feedback.SyncStatus
import app.epistola.suite.feedback.commands.UpdateFeedbackCommentExternalRef
import app.epistola.suite.feedback.commands.UpdateFeedbackSyncRef
import app.epistola.suite.feedback.commands.UpdateFeedbackSyncStatus
import app.epistola.suite.feedback.queries.GetFeedback
import app.epistola.suite.feedback.queries.GetFeedbackAssetContent
import app.epistola.suite.feedback.queries.ListFeedbackAssets
import app.epistola.suite.feedback.queries.ListPendingSyncFeedback
import app.epistola.suite.feedback.queries.ListUnsyncedComments
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorExecutionContext
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.scheduling.SchedulerLock
import app.epistola.suite.security.SecurityContext
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Periodically retries outbound syncs that failed their immediate push: feedback items still
 * PENDING (no hub ticket yet) and local comments on synced feedback that never got an external
 * id. Items that exceed max retries are marked FAILED and no longer retried.
 *
 * Only active when `epistola.feedback.sync.enabled` is true, and only does work when a real
 * sync target is wired (the support tier is enabled). In a multi-pod deployment [SchedulerLock]
 * ensures only one instance runs the sweep per cycle.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(
    name = ["epistola.feedback.sync.enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class FeedbackSyncScheduler(
    private val feedbackSyncPort: FeedbackSyncPort,
    private val mediator: Mediator,
    private val schedulerLock: SchedulerLock,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${epistola.feedback.sync.retry-interval-ms:60000}")
    fun retryPendingSync() {
        schedulerLock.runExclusively(SchedulerLock.FEEDBACK_RETRY) {
            MediatorExecutionContext.capture(mediator).bind {
                if (!feedbackSyncPort.isEnabled()) return@bind
                // Skip the whole sweep until registered, so we neither call the hub nor burn
                // sync attempts during the startup window before registration completes.
                if (!feedbackSyncPort.isReady()) {
                    log.debug("Feedback sync target not ready yet (installation not registered); skipping retry sweep")
                    return@bind
                }
                retryPendingFeedback()
                retryPendingComments()
            }
        }
    }

    private fun retryPendingFeedback() {
        val pending = ListPendingSyncFeedback(limit = 20).query()
        if (pending.isEmpty()) return

        log.debug("Retrying sync for {} pending feedback items", pending.size)

        for (feedback in pending) {
            // createTicket loads assets via tenant-scoped queries; bind a system principal.
            SecurityContext.runWithPrincipal(feedbackSystemPrincipal(feedback.tenantKey)) {
                try {
                    syncFeedback(feedback)
                    syncAttempts("success").increment()
                } catch (e: Exception) {
                    log.error("Failed to sync feedback {}: {}", feedback.id, e.message)
                    syncAttempts("failure").increment()
                    recordSyncAttemptFailure(feedback)
                }
            }
        }
    }

    // Per-item outcome of the outbound feedback sync. Lets a fleet alert on a rising
    // failure rate (provider down / misconfigured) and confirm the scheduler is doing work.
    private fun syncAttempts(outcome: String): Counter = Counter.builder("epistola.feedback.sync.attempts")
        .tag("outcome", outcome)
        .register(meterRegistry)

    private fun retryPendingComments() {
        val pending = ListUnsyncedComments(limit = 50).query()
        if (pending.isEmpty()) return

        log.debug("Retrying sync for {} unsynced feedback comments", pending.size)

        pending.groupBy { it.tenantKey to it.feedbackId }.forEach { (groupKey, comments) ->
            val (tenantKey, feedbackKey) = groupKey
            // GetFeedback is tenant-scoped (RequiresPermission); bind a system principal.
            SecurityContext.runWithPrincipal(feedbackSystemPrincipal(tenantKey)) {
                val feedbackId = FeedbackId(feedbackKey, TenantId(tenantKey))
                val feedback = GetFeedback(feedbackId).query() ?: return@runWithPrincipal
                if (feedback.syncStatus != SyncStatus.SYNCED || feedback.externalRef == null) {
                    return@runWithPrincipal
                }
                for (comment in comments) {
                    try {
                        val ref = feedbackSyncPort.addComment(feedback, comment)
                        UpdateFeedbackCommentExternalRef(
                            id = FeedbackCommentId(comment.id, feedbackId),
                            externalCommentId = ref.externalCommentId,
                        ).execute()
                        log.info("Re-synced comment {} for feedback {}", comment.id, feedback.id)
                    } catch (e: Exception) {
                        log.error("Failed to re-sync comment {}: {}", comment.id, e.message)
                    }
                }
            }
        }
    }

    private fun syncFeedback(feedback: Feedback) {
        val feedbackId = FeedbackId(feedback.id, TenantId(feedback.tenantKey))
        val assets = loadAssetContents(feedbackId)
        val syncResult = feedbackSyncPort.createTicket(feedback, assets)

        UpdateFeedbackSyncRef(
            id = feedbackId,
            externalRef = syncResult.externalRef,
            externalUrl = syncResult.externalUrl,
        ).execute()

        log.info("Successfully synced feedback {} to external ticket {}", feedback.id, syncResult.externalRef)
    }

    private fun loadAssetContents(feedbackId: FeedbackId): List<FeedbackAssetContent> {
        val assets = ListFeedbackAssets(feedbackId).query()
        return assets.mapNotNull { asset ->
            val assetId = FeedbackAssetId(asset.id, feedbackId)
            GetFeedbackAssetContent(assetId).query()
        }
    }

    private fun recordSyncAttemptFailure(feedback: Feedback) {
        val feedbackId = FeedbackId(feedback.id, TenantId(feedback.tenantKey))
        val nextAttempt = feedback.syncAttempts + 1
        if (nextAttempt >= ListPendingSyncFeedback.MAX_SYNC_ATTEMPTS) {
            log.warn("Feedback {} exceeded max sync attempts ({}), marking as FAILED", feedback.id, nextAttempt)
            UpdateFeedbackSyncStatus(id = feedbackId, syncStatus = SyncStatus.FAILED, incrementAttempts = true).execute()
        } else {
            log.debug("Feedback {} sync attempt {} failed, will retry", feedback.id, nextAttempt)
            UpdateFeedbackSyncStatus(id = feedbackId, syncStatus = SyncStatus.PENDING, incrementAttempts = true).execute()
        }
    }
}
