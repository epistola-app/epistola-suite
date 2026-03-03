package app.epistola.suite.feedback.sync.github

import app.epistola.suite.common.ids.FeedbackId
import app.epistola.suite.common.ids.FeedbackKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.feedback.FeedbackConfig
import app.epistola.suite.feedback.FeedbackStatus
import app.epistola.suite.feedback.commands.SyncFeedbackComment
import app.epistola.suite.feedback.commands.UpdateFeedbackStatus
import app.epistola.suite.feedback.sync.IssueSyncPort
import app.epistola.suite.mediator.execute
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Receives GitHub webhook events for feedback sync.
 *
 * This endpoint is optional and only active when `epistola.github.webhooks.enabled=true`.
 * Deployments behind a firewall that cannot receive webhooks will work fine with
 * outbound-only sync (feedback is created in GitHub but comments are not synced back).
 *
 * Handles:
 * - `issue_comment` events: syncs GitHub comments back to Epistola feedback
 * - `issues` events: syncs issue state changes (closed/reopened) back to feedback status
 *
 * Security: HMAC-SHA256 signature verification using the webhook secret.
 * No session, no CSRF — authenticated purely by webhook signature.
 */
@RestController
@RequestMapping("/feedback/github/webhooks")
@ConditionalOnProperty("epistola.github.webhooks.enabled", havingValue = "true")
class GitHubWebhookController(
    private val issueSyncPort: IssueSyncPort,
    private val objectMapper: ObjectMapper,
    private val properties: GitHubAppProperties,
    private val jdbi: Jdbi,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping
    fun handleWebhook(
        @RequestBody body: ByteArray,
        @RequestHeader("X-Hub-Signature-256") signature: String?,
        @RequestHeader("X-GitHub-Event") event: String?,
    ): ResponseEntity<Void> {
        if (signature == null) {
            log.warn("Webhook received without signature header")
            return ResponseEntity.status(401).build()
        }

        if (!issueSyncPort.verifyWebhookSignature(body, signature, properties.webhookSecret)) {
            log.warn("Webhook signature verification failed")
            return ResponseEntity.status(401).build()
        }

        val payload = objectMapper.readTree(body)

        when (event) {
            "issue_comment" -> handleIssueComment(payload)
            "issues" -> handleIssueStateChange(payload)
            else -> log.debug("Ignoring webhook event: {}", event)
        }

        return ResponseEntity.ok().build()
    }

    private fun handleIssueComment(payload: JsonNode) {
        val action = payload.path("action").stringValue() ?: return
        if (action != "created") return

        val repoFullName = payload.path("repository").path("full_name").stringValue() ?: return
        val issueNumber = payload.path("issue").path("number").intValue().toString()
        val issueLabels = payload.path("issue").path("labels")
            .mapNotNull { it.path("name").stringValue() }

        val config = findTenantConfig(repoFullName, issueLabels) ?: run {
            log.debug("No tenant config found for repo {} with labels {}", repoFullName, issueLabels)
            return
        }

        val feedbackId = findFeedbackByExternalRef(config.tenantKey, issueNumber) ?: run {
            log.debug("No feedback found for external ref {} in tenant {}", issueNumber, config.tenantKey)
            return
        }

        val comment = payload.path("comment")
        val externalCommentId = comment.path("id").longValue()
        val authorName = comment.path("user").path("login").stringValue() ?: "Unknown"
        val body = comment.path("body").stringValue() ?: ""

        try {
            SyncFeedbackComment(
                tenantKey = config.tenantKey,
                feedbackId = feedbackId.key,
                body = body,
                authorName = authorName,
                authorEmail = null,
                externalCommentId = externalCommentId,
            ).execute()

            log.info("Synced GitHub comment {} to feedback {}", externalCommentId, feedbackId.key)
        } catch (e: Exception) {
            log.error("Failed to sync comment {} for feedback {}: {}", externalCommentId, feedbackId.key, e.message)
        }
    }

    private fun handleIssueStateChange(payload: JsonNode) {
        val action = payload.path("action").stringValue() ?: return
        if (action != "closed" && action != "reopened") return

        val repoFullName = payload.path("repository").path("full_name").stringValue() ?: return
        val issueNumber = payload.path("issue").path("number").intValue().toString()
        val issueLabels = payload.path("issue").path("labels")
            .mapNotNull { it.path("name").stringValue() }

        val config = findTenantConfig(repoFullName, issueLabels) ?: return
        val feedbackId = findFeedbackByExternalRef(config.tenantKey, issueNumber) ?: return

        val newStatus = when (action) {
            "closed" -> FeedbackStatus.RESOLVED
            "reopened" -> FeedbackStatus.OPEN
            else -> return
        }

        try {
            UpdateFeedbackStatus(id = feedbackId, status = newStatus).execute()
            log.info("Updated feedback {} status to {} from GitHub issue state change", feedbackId.key, newStatus)
        } catch (e: Exception) {
            log.error("Failed to update feedback {} status: {}", feedbackId.key, e.message)
        }
    }

    /**
     * Find the tenant config by matching repo full name and labels.
     * The webhook payload's repo + tenant label must match a configured feedback_config row.
     */
    private fun findTenantConfig(repoFullName: String, issueLabels: List<String>): FeedbackConfig? {
        val configs = jdbi.withHandleUnchecked { handle ->
            handle.createQuery(
                """
                SELECT * FROM feedback_config
                WHERE enabled = true
                  AND repo_owner || '/' || repo_name = :repoFullName
                """,
            )
                .bind("repoFullName", repoFullName)
                .mapTo(FeedbackConfig::class.java)
                .list()
        }

        if (configs.isEmpty()) return null
        if (configs.size == 1) return configs.first()

        // Multiple tenants share the same repo — match by label
        return configs.firstOrNull { config ->
            config.label != null && issueLabels.contains(config.label)
        }
    }

    /**
     * Find a feedback item by its external_ref (GitHub issue number) in the given tenant.
     */
    private fun findFeedbackByExternalRef(
        tenantKey: TenantKey,
        externalRef: String,
    ): FeedbackId? {
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
}
