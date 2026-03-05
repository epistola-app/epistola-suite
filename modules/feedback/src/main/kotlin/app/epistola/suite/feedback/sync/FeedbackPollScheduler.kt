package app.epistola.suite.feedback.sync

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.feedback.FeedbackSyncConfig
import app.epistola.suite.feedback.commands.SyncFeedbackComment
import app.epistola.suite.feedback.commands.UpdateFeedbackStatus
import app.epistola.suite.feedback.commands.UpdateFeedbackSyncConfigLastPolledAt
import app.epistola.suite.feedback.queries.GetFeedbackByExternalRef
import app.epistola.suite.feedback.queries.ListEnabledFeedbackSyncConfigs
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Periodically polls external issue trackers for inbound updates (comments, status changes).
 *
 * This is the alternative to webhooks for deployments behind firewalls that cannot
 * receive inbound HTTP calls. Only active when `epistola.feedback.sync.polling.enabled=true`.
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
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${epistola.feedback.sync.polling.interval-ms:300000}")
    fun pollForUpdates() = MediatorContext.runWithMediator(mediator) {
        val configs = ListEnabledFeedbackSyncConfigs.query()
        if (configs.isEmpty()) return@runWithMediator

        for (config in configs) {
            try {
                pollTenant(config)
            } catch (e: Exception) {
                log.error("Failed to poll updates for tenant {}: {}", config.tenantKey, e.message, e)
            }
        }
    }

    private fun pollTenant(config: FeedbackSyncConfig) {
        val since = config.lastPolledAt ?: Instant.EPOCH
        val updates = feedbackSyncPort.fetchUpdates(config, since)

        if (updates.isEmpty()) {
            UpdateFeedbackSyncConfigLastPolledAt(config.tenantKey, Instant.now()).execute()
            return
        }

        log.info("Fetched {} updates for tenant {} since {}", updates.size, config.tenantKey, since)

        var lastSuccessfulTimestamp = since
        for (update in updates) {
            try {
                processUpdate(config.tenantKey, update)
                if (update.occurredAt.isAfter(lastSuccessfulTimestamp)) {
                    lastSuccessfulTimestamp = update.occurredAt
                }
            } catch (e: Exception) {
                log.error(
                    "Failed to process update for tenant {} (ref={}): {}",
                    config.tenantKey,
                    update.externalRef,
                    e.message,
                )
            }
        }

        // Only advance the poll cursor to the last successfully processed update
        if (lastSuccessfulTimestamp.isAfter(since)) {
            UpdateFeedbackSyncConfigLastPolledAt(config.tenantKey, lastSuccessfulTimestamp).execute()
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
}
