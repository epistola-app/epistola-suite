package app.epistola.suite.feedback.sync

import app.epistola.suite.common.ids.FeedbackAssetId
import app.epistola.suite.common.ids.FeedbackId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.feedback.Feedback
import app.epistola.suite.feedback.FeedbackAssetContent
import app.epistola.suite.feedback.SyncStatus
import app.epistola.suite.feedback.commands.UpdateFeedbackSyncRef
import app.epistola.suite.feedback.queries.GetFeedbackAssetContent
import app.epistola.suite.feedback.queries.GetFeedbackSyncConfig
import app.epistola.suite.feedback.queries.ListFeedbackAssets
import app.epistola.suite.feedback.queries.ListPendingSyncFeedback
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
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
 * Only active when `epistola.feedback.sync.enabled` is true (requires a sync provider configured).
 * Items that exceed max retries are marked as FAILED and no longer retried.
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
    private val jdbi: Jdbi,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${epistola.feedback.sync.retry-interval-ms:60000}")
    fun retryPendingSync() = MediatorContext.runWithMediator(mediator) {
        val pending = ListPendingSyncFeedback(limit = 20).query()
        if (pending.isEmpty()) return@runWithMediator

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
        val config = GetFeedbackSyncConfig(feedback.tenantKey).query() ?: run {
            log.warn("No feedback sync config for tenant {}, marking as NOT_CONFIGURED", feedback.tenantKey)
            markNotConfigured(feedback)
            return
        }

        if (!config.enabled) {
            log.warn("Sync not enabled for tenant {}, marking as NOT_CONFIGURED", feedback.tenantKey)
            markNotConfigured(feedback)
            return
        }

        val feedbackId = FeedbackId(feedback.id, TenantId(feedback.tenantKey))
        val assets = loadAssetContents(feedbackId)
        val syncResult = feedbackSyncPort.createTicket(config, feedback, assets)

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
