package app.epistola.suite.feedback.sync

import app.epistola.suite.common.ids.FeedbackId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.feedback.Feedback
import app.epistola.suite.feedback.SyncStatus
import app.epistola.suite.feedback.commands.UpdateFeedbackSyncRef
import app.epistola.suite.feedback.queries.GetFeedbackConfig
import app.epistola.suite.feedback.queries.ListPendingSyncFeedback
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Periodically retries syncing feedback items that failed initial sync.
 *
 * Only active when `epistola.github.sync.enabled` is true (requires GitHub App configured).
 * Items that exceed max retries are marked as FAILED and no longer retried.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(
    name = ["epistola.github.sync.enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class FeedbackSyncScheduler(
    private val issueSyncPort: IssueSyncPort,
    private val jdbi: Jdbi,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${epistola.github.sync.retry-interval-ms:60000}")
    fun retryPendingSync() {
        val pending = ListPendingSyncFeedback(limit = 20).query()
        if (pending.isEmpty()) return

        log.debug("Retrying sync for {} pending feedback items", pending.size)

        for (feedback in pending) {
            try {
                syncFeedback(feedback)
            } catch (e: Exception) {
                log.error("Failed to sync feedback {}: {}", feedback.id, e.message)
                markFailed(feedback)
            }
        }
    }

    private fun syncFeedback(feedback: Feedback) {
        val config = GetFeedbackConfig(feedback.tenantKey).query() ?: run {
            log.warn("No feedback config for tenant {}, marking as NOT_CONFIGURED", feedback.tenantKey)
            markNotConfigured(feedback)
            return
        }

        if (!config.enabled || !config.isGitHubConfigured) {
            log.warn("GitHub not configured/enabled for tenant {}, marking as NOT_CONFIGURED", feedback.tenantKey)
            markNotConfigured(feedback)
            return
        }

        val syncResult = issueSyncPort.createIssue(config, feedback, screenshot = null)

        val feedbackId = FeedbackId(feedback.id, TenantId(feedback.tenantKey))
        UpdateFeedbackSyncRef(
            id = feedbackId,
            externalRef = syncResult.externalRef,
            externalUrl = syncResult.externalUrl,
        ).execute()

        log.info("Successfully synced feedback {} to external issue {}", feedback.id, syncResult.externalRef)
    }

    private fun markFailed(feedback: Feedback) {
        updateSyncStatus(feedback, SyncStatus.FAILED)
    }

    private fun markNotConfigured(feedback: Feedback) {
        updateSyncStatus(feedback, SyncStatus.NOT_CONFIGURED)
    }

    private fun updateSyncStatus(feedback: Feedback, status: SyncStatus) {
        jdbi.withHandleUnchecked { handle ->
            handle.createUpdate(
                """
                UPDATE feedback
                SET sync_status = :syncStatus, updated_at = NOW()
                WHERE tenant_key = :tenantKey AND id = :id
                """,
            )
                .bind("syncStatus", status.name)
                .bind("tenantKey", feedback.tenantKey)
                .bind("id", feedback.id.value)
                .execute()
        }
    }
}
