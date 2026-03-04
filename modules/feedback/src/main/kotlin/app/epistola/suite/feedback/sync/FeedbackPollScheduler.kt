package app.epistola.suite.feedback.sync

import app.epistola.suite.common.ids.FeedbackId
import app.epistola.suite.common.ids.FeedbackKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.feedback.FeedbackSyncConfig
import app.epistola.suite.feedback.commands.SyncFeedbackComment
import app.epistola.suite.feedback.commands.UpdateFeedbackStatus
import app.epistola.suite.mediator.execute
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
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
    private val jdbi: Jdbi,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${epistola.feedback.sync.polling.interval-ms:300000}")
    fun pollForUpdates() {
        val configs = findEnabledConfigs()
        if (configs.isEmpty()) return

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
            updateLastPolledAt(config.tenantKey)
            return
        }

        log.info("Fetched {} updates for tenant {} since {}", updates.size, config.tenantKey, since)

        for (update in updates) {
            try {
                processUpdate(config.tenantKey, update)
            } catch (e: Exception) {
                log.error(
                    "Failed to process update for tenant {} (ref={}): {}",
                    config.tenantKey,
                    update.externalRef,
                    e.message,
                )
            }
        }

        updateLastPolledAt(config.tenantKey)
    }

    private fun processUpdate(tenantKey: TenantKey, update: ExternalUpdate) {
        val feedbackId = findFeedbackByExternalRef(tenantKey, update.externalRef) ?: run {
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

    private fun findEnabledConfigs(): List<FeedbackSyncConfig> = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            """
            SELECT * FROM feedback_sync_config
            WHERE enabled = true
            """,
        )
            .mapTo(FeedbackSyncConfig::class.java)
            .list()
    }

    private fun findFeedbackByExternalRef(tenantKey: TenantKey, externalRef: String): FeedbackId? {
        val feedbackKey = jdbi.withHandleUnchecked { handle ->
            handle.createQuery(
                """
                SELECT id FROM feedback
                WHERE tenant_key = :tenantKey AND external_ref = :externalRef
                """,
            )
                .bind("tenantKey", tenantKey)
                .bind("externalRef", externalRef)
                .mapTo(FeedbackKey::class.java)
                .findOne()
                .orElse(null)
        }

        return feedbackKey?.let { FeedbackId(it, TenantId(tenantKey)) }
    }

    private fun updateLastPolledAt(tenantKey: TenantKey) {
        jdbi.withHandleUnchecked { handle ->
            handle.createUpdate(
                """
                UPDATE feedback_sync_config
                SET last_polled_at = NOW()
                WHERE tenant_key = :tenantKey
                """,
            )
                .bind("tenantKey", tenantKey)
                .execute()
        }
    }
}
