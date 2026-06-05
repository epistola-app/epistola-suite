package app.epistola.suite.feedback.sync

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.feedback.commands.SyncFeedbackComment
import app.epistola.suite.feedback.commands.UpdateFeedbackStatus
import app.epistola.suite.feedback.queries.GetFeedbackByExternalRef
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.metadata.getAs
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Periodically polls the external sync target for inbound updates (comments, status changes).
 *
 * Sync is installation-wide: one poll returns updates across all tenants, each tagged with
 * its tenant. The poll cursor is a single installation-level timestamp persisted in
 * `app_metadata` under [CURSOR_KEY]. Only active when
 * `epistola.feedback.sync.polling.enabled=true`, and only does work when a real sync target
 * is wired (the support tier is enabled).
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
    private val mediator: Mediator,
    private val appMetadata: AppMetadataService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${epistola.feedback.sync.polling.interval-ms:300000}")
    fun pollForUpdates() = MediatorContext.runWithMediator(mediator) {
        if (!feedbackSyncPort.isEnabled()) return@runWithMediator

        val since = loadCursor()
        val updates = feedbackSyncPort.fetchUpdates(since)

        if (updates.isEmpty()) {
            saveCursor(Instant.now())
            return@runWithMediator
        }

        log.info("Fetched {} feedback updates since {}", updates.size, since)

        var lastSuccessfulTimestamp = since
        for (update in updates) {
            try {
                processUpdate(update.tenantKey, update)
                if (update.occurredAt.isAfter(lastSuccessfulTimestamp)) {
                    lastSuccessfulTimestamp = update.occurredAt
                }
            } catch (e: Exception) {
                log.error(
                    "Failed to process update for tenant {} (ref={}): {}",
                    update.tenantKey,
                    update.externalRef,
                    e.message,
                )
            }
        }

        // Only advance the poll cursor to the last successfully processed update.
        if (lastSuccessfulTimestamp.isAfter(since)) {
            saveCursor(lastSuccessfulTimestamp)
        }
    }

    private fun processUpdate(tenantKey: TenantKey, update: ExternalUpdate) {
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
                UpdateFeedbackStatus(id = feedbackId, status = update.newStatus).execute()
                log.debug("Updated feedback {} status to {}", feedbackId.key, update.newStatus)
            }
        }
    }

    private fun loadCursor(): Instant = appMetadata.getAs<Cursor>(CURSOR_KEY)?.let { Instant.ofEpochMilli(it.lastPolledAtEpochMillis) } ?: Instant.EPOCH

    private fun saveCursor(at: Instant) {
        appMetadata.setAs(CURSOR_KEY, Cursor(at.toEpochMilli()))
    }

    /** Installation-wide poll cursor, stored as JSON in `app_metadata`. */
    data class Cursor(
        val lastPolledAtEpochMillis: Long,
    )

    companion object {
        const val CURSOR_KEY = "feedback.sync.lastPolledAt"
    }
}
