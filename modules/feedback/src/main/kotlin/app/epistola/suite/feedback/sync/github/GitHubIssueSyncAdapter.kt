package app.epistola.suite.feedback.sync.github

import app.epistola.suite.feedback.Feedback
import app.epistola.suite.feedback.FeedbackComment
import app.epistola.suite.feedback.FeedbackStatus
import app.epistola.suite.feedback.FeedbackSyncConfig
import app.epistola.suite.feedback.sync.ExternalCommentRef
import app.epistola.suite.feedback.sync.ExternalUpdate
import app.epistola.suite.feedback.sync.FeedbackSyncPort
import app.epistola.suite.feedback.sync.SyncResult
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/**
 * GitHub implementation of [FeedbackSyncPort].
 *
 * Creates issues, adds comments, updates status, and polls for updates via the GitHub REST API v3.
 * Authentication uses a per-tenant Personal Access Token (PAT) stored in [GitHubSyncSettings].
 *
 * Provider-specific settings are parsed from [FeedbackSyncConfig.settings] JSONB
 * into [GitHubSyncSettings] at the start of each method call.
 */
class GitHubIssueSyncAdapter(
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
) : FeedbackSyncPort {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun createTicket(config: FeedbackSyncConfig, feedback: Feedback, screenshot: ByteArray?): SyncResult {
        val settings = parseSettings(config)
        val token = settings.personalAccessToken

        val body = buildIssueBody(feedback)
        val labels = buildList {
            add("feedback")
            add(feedback.category.name.lowercase().replace('_', '-'))
            add("priority:${feedback.priority.name.lowercase()}")
            settings.label?.let { add(it) }
        }

        val requestBody = mapOf(
            "title" to feedback.title,
            "body" to body,
            "labels" to labels,
        )

        val response = restClient.post()
            .uri("/repos/{owner}/{repo}/issues", settings.repoOwner, settings.repoName)
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
            settings.repoFullName,
        )

        return SyncResult(
            externalRef = issueNumber.toString(),
            externalUrl = htmlUrl,
        )
    }

    override fun addComment(
        config: FeedbackSyncConfig,
        externalRef: String,
        comment: FeedbackComment,
    ): ExternalCommentRef {
        val settings = parseSettings(config)
        val token = settings.personalAccessToken

        val body = "**${comment.authorName}** commented:\n\n${comment.body}"

        val requestBody = mapOf("body" to body)

        val response = restClient.post()
            .uri(
                "/repos/{owner}/{repo}/issues/{issueNumber}/comments",
                settings.repoOwner,
                settings.repoName,
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

        log.info("Added comment {} to GitHub issue #{} in {}", commentId, externalRef, settings.repoFullName)

        return ExternalCommentRef(externalCommentId = commentId.toString())
    }

    override fun updateStatus(config: FeedbackSyncConfig, externalRef: String, status: FeedbackStatus) {
        val settings = parseSettings(config)
        val token = settings.personalAccessToken

        val state = when (status) {
            FeedbackStatus.CLOSED, FeedbackStatus.RESOLVED -> "closed"
            else -> "open"
        }

        val requestBody = mapOf("state" to state)

        restClient.patch()
            .uri(
                "/repos/{owner}/{repo}/issues/{issueNumber}",
                settings.repoOwner,
                settings.repoName,
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
            settings.repoFullName,
        )
    }

    override fun fetchUpdates(config: FeedbackSyncConfig, since: Instant): List<ExternalUpdate> {
        val settings = parseSettings(config)
        val token = settings.personalAccessToken
        val sinceIso = since.toString()

        val updates = mutableListOf<ExternalUpdate>()

        // Fetch issue state changes (closed/reopened) since the given timestamp
        fetchIssueStateChanges(settings, token, sinceIso, updates)

        // Fetch new comments since the given timestamp
        fetchIssueComments(settings, token, sinceIso, updates)

        return updates
    }

    private fun fetchIssueStateChanges(
        settings: GitHubSyncSettings,
        token: String,
        since: String,
        updates: MutableList<ExternalUpdate>,
    ) {
        try {
            val uri = buildString {
                append("/repos/${settings.repoOwner}/${settings.repoName}/issues")
                append("?state=all&since=$since&sort=updated&direction=asc&per_page=100")
                settings.label?.let { append("&labels=$it") }
            }

            val response = restClient.get()
                .uri(uri)
                .header("Authorization", "Bearer $token")
                .retrieve()
                .body(String::class.java) ?: return

            val issues = objectMapper.readTree(response)
            for (issue in issues) {
                val issueNumber = issue.path("number").intValue().toString()
                val state = issue.path("state").stringValue() ?: continue
                val updatedAt = issue.path("updated_at").stringValue() ?: continue

                val newStatus = when (state) {
                    "closed" -> FeedbackStatus.RESOLVED
                    "open" -> FeedbackStatus.OPEN
                    else -> continue
                }

                updates.add(
                    ExternalUpdate.StatusChange(
                        externalRef = issueNumber,
                        occurredAt = Instant.parse(updatedAt),
                        newStatus = newStatus,
                    ),
                )
            }
        } catch (e: Exception) {
            log.warn("Failed to fetch issue state changes from {}: {}", settings.repoFullName, e.message)
        }
    }

    private fun fetchIssueComments(
        settings: GitHubSyncSettings,
        token: String,
        since: String,
        updates: MutableList<ExternalUpdate>,
    ) {
        try {
            val uri = "/repos/${settings.repoOwner}/${settings.repoName}/issues/comments" +
                "?since=$since&sort=created&direction=asc&per_page=100"

            val response = restClient.get()
                .uri(uri)
                .header("Authorization", "Bearer $token")
                .retrieve()
                .body(String::class.java) ?: return

            val comments = objectMapper.readTree(response)
            for (comment in comments) {
                val commentId = comment.path("id").longValue().toString()
                val issueUrl = comment.path("issue_url").stringValue() ?: continue
                val issueNumber = issueUrl.substringAfterLast("/")
                val createdAt = comment.path("created_at").stringValue() ?: continue
                val authorName = comment.path("user").path("login").stringValue() ?: "Unknown"
                val body = comment.path("body").stringValue() ?: ""

                updates.add(
                    ExternalUpdate.Comment(
                        externalRef = issueNumber,
                        occurredAt = Instant.parse(createdAt),
                        externalCommentId = commentId,
                        authorName = authorName,
                        authorEmail = null,
                        body = body,
                    ),
                )
            }
        } catch (e: Exception) {
            log.warn("Failed to fetch issue comments from {}: {}", settings.repoFullName, e.message)
        }
    }

    private fun parseSettings(config: FeedbackSyncConfig): GitHubSyncSettings = objectMapper.readValue(config.settings, GitHubSyncSettings::class.java)

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
}

class GitHubSyncException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
