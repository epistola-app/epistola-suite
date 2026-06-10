package app.epistola.suite.feedback.sync

import app.epistola.suite.background.BackgroundExecutionContext
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.feedback.commands.SyncFeedbackComment
import app.epistola.suite.feedback.commands.SyncFeedbackStatus
import app.epistola.suite.feedback.queries.GetFeedbackByExternalRef
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.metadata.getAs
import app.epistola.suite.scheduling.SchedulerLock
import app.epistola.suite.security.SecurityContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Periodically polls the external sync target for inbound updates (comments, status changes).
 *
 * Sync is installation-wide: one poll returns updates across all tenants, each tagged with
 * its tenant. The poll cursor is a single installation-level **sequence number** persisted in
 * `app_metadata` under [CURSOR_KEY]; the feed is gap-free and ordered by that seq, so the
 * cursor advances only past updates actually processed and never moves on an empty poll
 * (no wall-clock involved). Only active when `epistola.feedback.sync.polling.enabled=true`,
 * and only does work when a real sync target is wired (the support tier is enabled).
 *
 * In a multi-pod deployment every instance schedules this; [SchedulerLock] ensures only one
 * polls per cycle.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(
    name = ["epistola.feedback.sync.polling.enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class FeedbackPollScheduler(
    private val feedbackSyncPort: FeedbackSyncPort,
    private val backgroundExecutionContext: BackgroundExecutionContext,
    private val appMetadata: AppMetadataService,
    private val schedulerLock: SchedulerLock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${epistola.feedback.sync.polling.interval-ms:300000}")
    fun pollForUpdates() {
        schedulerLock.runExclusively(SchedulerLock.FEEDBACK_POLL) {
            backgroundExecutionContext.run { drainUpdates() }
        }
    }

    private fun drainUpdates() {
        if (!feedbackSyncPort.isEnabled()) return
        if (!feedbackSyncPort.isReady()) {
            log.debug("Feedback sync target not ready yet (installation not registered); skipping poll")
            return
        }

        var cursor = loadCursor()
        var pages = 0
        while (pages++ < MAX_PAGES_PER_RUN) {
            val page = feedbackSyncPort.fetchUpdates(cursor)
            if (page.updates.isEmpty()) {
                // Nothing new — leave the cursor untouched (it is the hub's seq, not a clock).
                break
            }

            log.info("Fetched {} feedback updates after seq {}", page.updates.size, cursor)

            var lastProcessed = cursor
            var stopped = false
            for (update in page.updates) {
                try {
                    processUpdate(update.tenantKey, update)
                    lastProcessed = update.seq
                } catch (e: Exception) {
                    log.error(
                        "Failed to process update seq {} for tenant {} (ref={}): {}",
                        update.seq,
                        update.tenantKey,
                        update.externalRef,
                        e.message,
                    )
                    // Stop at the first failure so the cursor doesn't skip past it; retry next cycle.
                    stopped = true
                    break
                }
            }

            if (lastProcessed > cursor) {
                saveCursor(lastProcessed)
                cursor = lastProcessed
            }

            if (stopped || !page.hasMore) break
        }
    }

    private fun processUpdate(tenantKey: TenantKey, update: ExternalUpdate) {
        // The poll runs on a scheduler thread with no HTTP principal; the inbound commands
        // are tenant-scoped (RequiresPermission), so bind a system principal for the tenant.
        SecurityContext.runWithPrincipal(feedbackSystemPrincipal(tenantKey)) {
            applyUpdate(tenantKey, update)
        }
    }

    private fun applyUpdate(tenantKey: TenantKey, update: ExternalUpdate) {
        val feedbackId = GetFeedbackByExternalRef(tenantKey, update.externalRef).query() ?: run {
            log.debug("No feedback found for external ref {} in tenant {}", update.externalRef, tenantKey)
            return
        }

        when (update) {
            is ExternalUpdate.Comment -> {
                SyncFeedbackComment(
                    tenantKey = tenantKey,
                    feedbackId = feedbackId.key,
                    body = update.body,
                    authorName = update.authorName,
                    authorEmail = update.authorEmail,
                    externalCommentId = update.externalCommentId,
                ).execute()
                log.debug("Synced comment {} for feedback {}", update.externalCommentId, feedbackId.key)
            }

            is ExternalUpdate.StatusChange -> {
                // SyncFeedbackStatus (not UpdateFeedbackStatus) so this does not bounce back out.
                SyncFeedbackStatus(id = feedbackId, status = update.newStatus).execute()
                log.debug("Updated feedback {} status to {}", feedbackId.key, update.newStatus)
            }
        }
    }

    private fun loadCursor(): Long = appMetadata.getAs<Cursor>(CURSOR_KEY)?.seq ?: 0L

    private fun saveCursor(seq: Long) {
        appMetadata.setAs(CURSOR_KEY, Cursor(seq))
    }

    /** Installation-wide poll cursor (the hub feed sequence), stored as JSON in `app_metadata`. */
    data class Cursor(
        val seq: Long,
    )

    companion object {
        const val CURSOR_KEY = "feedback.sync.lastSeq"

        /** Bound on pages drained per cycle so one run can't loop unbounded on a large backlog. */
        private const val MAX_PAGES_PER_RUN = 50
    }
}
