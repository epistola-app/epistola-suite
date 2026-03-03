package app.epistola.suite.feedback.sync.github

import app.epistola.suite.feedback.Feedback
import app.epistola.suite.feedback.FeedbackComment
import app.epistola.suite.feedback.FeedbackConfig
import app.epistola.suite.feedback.FeedbackStatus
import app.epistola.suite.feedback.sync.ExternalCommentRef
import app.epistola.suite.feedback.sync.IssueSyncPort
import app.epistola.suite.feedback.sync.SyncResult
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * GitHub App implementation of [IssueSyncPort].
 *
 * Creates issues, adds comments, and updates status via the GitHub REST API v3.
 * Authentication uses installation access tokens obtained via [GitHubAppAuthService].
 */
class GitHubIssueSyncAdapter(
    private val authService: GitHubAppAuthService,
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
) : IssueSyncPort {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun createIssue(config: FeedbackConfig, feedback: Feedback, screenshot: ByteArray?): SyncResult {
        val installationId = config.installationId
            ?: throw IllegalArgumentException("GitHub not configured for tenant ${config.tenantKey}")
        val token = authService.getInstallationToken(installationId)

        val body = buildIssueBody(feedback)
        val labels = buildList {
            add("feedback")
            add(feedback.category.name.lowercase().replace('_', '-'))
            add("priority:${feedback.priority.name.lowercase()}")
            config.label?.let { add(it) }
        }

        val requestBody = mapOf(
            "title" to feedback.title,
            "body" to body,
            "labels" to labels,
        )

        val response = restClient.post()
            .uri("/repos/{owner}/{repo}/issues", config.repoOwner, config.repoName)
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .body(requestBody)
            .retrieve()
            .body(String::class.java)
            ?: throw GitHubSyncException("Empty response when creating issue")

        val json = objectMapper.readTree(response)
        val issueNumber = json.path("number").intValue()
            .takeIf { it > 0 }
            ?: throw GitHubSyncException("No issue number in response")
        val htmlUrl = json.path("html_url").stringValue()
            ?: throw GitHubSyncException("No HTML URL in response")

        log.info(
            "Created GitHub issue #{} for feedback {} in {}",
            issueNumber,
            feedback.id,
            config.repoFullName,
        )

        return SyncResult(
            externalRef = issueNumber.toString(),
            externalUrl = htmlUrl,
        )
    }

    override fun addComment(
        config: FeedbackConfig,
        externalRef: String,
        comment: FeedbackComment,
    ): ExternalCommentRef {
        val installationId = config.installationId
            ?: throw IllegalArgumentException("GitHub not configured for tenant ${config.tenantKey}")
        val token = authService.getInstallationToken(installationId)

        val body = "**${comment.authorName}** commented:\n\n${comment.body}"

        val requestBody = mapOf("body" to body)

        val response = restClient.post()
            .uri(
                "/repos/{owner}/{repo}/issues/{issueNumber}/comments",
                config.repoOwner,
                config.repoName,
                externalRef,
            )
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .body(requestBody)
            .retrieve()
            .body(String::class.java)
            ?: throw GitHubSyncException("Empty response when adding comment")

        val json = objectMapper.readTree(response)
        val commentId = json.path("id").longValue()
            .takeIf { it > 0 }
            ?: throw GitHubSyncException("No comment ID in response")

        log.info("Added comment {} to GitHub issue #{} in {}", commentId, externalRef, config.repoFullName)

        return ExternalCommentRef(externalCommentId = commentId)
    }

    override fun updateStatus(config: FeedbackConfig, externalRef: String, status: FeedbackStatus) {
        val installationId = config.installationId
            ?: throw IllegalArgumentException("GitHub not configured for tenant ${config.tenantKey}")
        val token = authService.getInstallationToken(installationId)

        val state = when (status) {
            FeedbackStatus.CLOSED, FeedbackStatus.RESOLVED -> "closed"
            else -> "open"
        }

        val requestBody = mapOf("state" to state)

        restClient.patch()
            .uri(
                "/repos/{owner}/{repo}/issues/{issueNumber}",
                config.repoOwner,
                config.repoName,
                externalRef,
            )
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .body(requestBody)
            .retrieve()
            .toBodilessEntity()

        log.info(
            "Updated GitHub issue #{} to state '{}' in {}",
            externalRef,
            state,
            config.repoFullName,
        )
    }

    override fun verifyWebhookSignature(payload: ByteArray, signature: String, secret: String): Boolean {
        if (!signature.startsWith("sha256=")) return false

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val computed = mac.doFinal(payload)
        val expected = signature.removePrefix("sha256=").hexToByteArray()

        return computed.contentEquals(expected)
    }

    private fun buildIssueBody(feedback: Feedback): String = buildString {
        appendLine(feedback.description)
        appendLine()
        appendLine("---")
        appendLine("**Category:** ${feedback.category.name}")
        appendLine("**Priority:** ${feedback.priority.name}")

        feedback.sourceUrl?.let {
            appendLine("**Source URL:** $it")
        }

        feedback.consoleLogs?.let { logs ->
            appendLine()
            appendLine("<details>")
            appendLine("<summary>Console Logs</summary>")
            appendLine()
            appendLine("```")
            appendLine(logs)
            appendLine("```")
            appendLine("</details>")
        }

        appendLine()
        appendLine("*Submitted via Epistola*")
    }

    private fun String.hexToByteArray(): ByteArray {
        check(length % 2 == 0) { "Hex string must have even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

class GitHubSyncException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
