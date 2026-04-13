package app.epistola.suite.handlers

import app.epistola.suite.common.ids.FeedbackAssetId
import app.epistola.suite.common.ids.FeedbackAssetKey
import app.epistola.suite.common.ids.FeedbackCommentId
import app.epistola.suite.common.ids.FeedbackCommentKey
import app.epistola.suite.common.ids.FeedbackId
import app.epistola.suite.common.ids.FeedbackKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.feedback.FeedbackAccessDeniedException
import app.epistola.suite.feedback.FeedbackCategory
import app.epistola.suite.feedback.FeedbackPriority
import app.epistola.suite.feedback.FeedbackStatus
import app.epistola.suite.feedback.SyncProviderType
import app.epistola.suite.feedback.commands.AddFeedbackAsset
import app.epistola.suite.feedback.commands.AddFeedbackComment
import app.epistola.suite.feedback.commands.CreateFeedback
import app.epistola.suite.feedback.commands.SaveFeedbackSyncConfig
import app.epistola.suite.feedback.commands.UpdateFeedbackStatus
import app.epistola.suite.feedback.queries.GetFeedback
import app.epistola.suite.feedback.queries.GetFeedbackAssetContent
import app.epistola.suite.feedback.queries.GetFeedbackComments
import app.epistola.suite.feedback.queries.GetFeedbackSyncConfig
import app.epistola.suite.feedback.queries.ListFeedback
import app.epistola.suite.feedback.queries.ListFeedbackAssets
import app.epistola.suite.feedback.sync.github.GitHubSyncSettings
import app.epistola.suite.htmx.feedbackId
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.tenants.queries.GetTenant
import org.slf4j.LoggerFactory
import org.springframework.boot.info.BuildProperties
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.util.concurrent.TimeUnit

@Component
class FeedbackHandler(
    private val buildProperties: BuildProperties?,
    private val objectMapper: tools.jackson.databind.ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun list(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val tenant = GetTenant(tenantId.key).query() ?: return ServerResponse.notFound().build()

        val statusFilter = request.param("status").orElse(null)
            ?.let { runCatching { FeedbackStatus.valueOf(it) }.getOrNull() }
        val categoryFilter = request.param("category").orElse(null)
            ?.let { runCatching { FeedbackCategory.valueOf(it) }.getOrNull() }

        val feedbackItems = ListFeedback(
            tenantKey = tenantId.key,
            status = statusFilter,
            category = categoryFilter,
        ).query()

        return ServerResponse.ok().page("feedback/list") {
            "pageTitle" to "Feedback - Epistola"
            "tenant" to tenant
            "tenantId" to tenantId.key
            "feedbackItems" to feedbackItems
            "activeNavSection" to "feedback"
            "statuses" to FeedbackStatus.entries
            "categories" to FeedbackCategory.entries
            "selectedStatus" to statusFilter
            "selectedCategory" to categoryFilter
        }
    }

    fun search(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val sourceUrl = request.param("url").orElse(null)

        val statusFilter = request.param("status").orElse(null)
            ?.let { runCatching { FeedbackStatus.valueOf(it) }.getOrNull() }
        val categoryFilter = request.param("category").orElse(null)
            ?.let { runCatching { FeedbackCategory.valueOf(it) }.getOrNull() }

        val feedbackItems = ListFeedback(
            tenantKey = tenantId.key,
            status = statusFilter,
            category = categoryFilter,
            sourceUrl = sourceUrl,
        ).query()

        if (sourceUrl != null) {
            return request.htmx {
                fragment("feedback/page-issues", "content") {
                    "tenantId" to tenantId.key
                    "feedbackItems" to feedbackItems
                }
                onNonHtmx { redirect("/tenants/${tenantId.key}/feedback") }
            }
        }

        return request.htmx {
            fragment("feedback/list", "rows") {
                "tenantId" to tenantId.key
                "feedbackItems" to feedbackItems
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/feedback") }
        }
    }

    fun detail(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val feedbackId = request.feedbackId(tenantId) ?: return ServerResponse.badRequest().build()

        val feedback = GetFeedback(feedbackId).query() ?: return ServerResponse.notFound().build()

        // Access control: admin or creator only
        val principal = SecurityContext.current()
        if (principal.userId != feedback.createdBy && !principal.isManager(tenantId.key)) {
            throw FeedbackAccessDeniedException(tenantId.key, feedbackId.key)
        }

        val comments = GetFeedbackComments(feedbackId).query()
        val tenant = GetTenant(tenantId.key).query()
        val assets = ListFeedbackAssets(feedbackId).query()

        return ServerResponse.ok().page("feedback/detail") {
            "pageTitle" to "${feedback.title} - Feedback - Epistola"
            "tenant" to tenant
            "tenantId" to tenantId.key
            "feedback" to feedback
            "comments" to comments
            "assets" to assets
            "activeNavSection" to "feedback"
            "statuses" to FeedbackStatus.entries
            "isManager" to principal.isManager(tenantId.key)
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()

        val form = request.form {
            field("title") { required() }
            field("description") { required() }
            field("category") { required() }
            field("priority") { required() }
        }

        if (form.hasErrors()) {
            return ServerResponse.ok().page("feedback/submit-form") {
                "tenantId" to tenantId.key
                "formData" to form.formData
                "errors" to form.errors
                "categories" to FeedbackCategory.entries
                "priorities" to FeedbackPriority.entries
                "appVersion" to (buildProperties?.version ?: "dev")
            }
        }

        val principal = SecurityContext.current()
        val category = FeedbackCategory.valueOf(form["category"])
        val priority = FeedbackPriority.valueOf(form["priority"])
        val sourceUrl = form.formData["sourceUrl"]
        val consoleLogs = form.formData["consoleLogs"]
        val metadata = form.formData["metadata"]
        val screenshotData = form.formData["screenshotData"]

        val feedbackId = FeedbackId(FeedbackKey.generate(), TenantId(tenantId.key))

        CreateFeedback(
            id = feedbackId,
            title = form["title"],
            description = form["description"],
            category = category,
            priority = priority,
            sourceUrl = sourceUrl,
            consoleLogs = consoleLogs,
            metadata = metadata,
            createdBy = principal.userId,
        ).execute()

        // Store screenshot as feedback asset if provided
        screenshotData?.let { dataUrl ->
            val assetId = FeedbackAssetId(FeedbackAssetKey.generate(), feedbackId)
            AddFeedbackAsset.fromDataUrl(assetId, dataUrl)?.let { command ->
                try {
                    command.execute()
                } catch (e: Exception) {
                    log.warn("Failed to store feedback screenshot: {}", e.message)
                }
            }
        }

        return request.htmx {
            fragment("feedback/submit-success") {}
            onNonHtmx { redirect("/tenants/${tenantId.key}/feedback") }
        }
    }

    fun updateStatus(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val feedbackId = request.feedbackId(tenantId) ?: return ServerResponse.badRequest().build()

        val form = request.form {
            field("status") { required() }
        }

        if (form.hasErrors()) return ServerResponse.badRequest().build()

        val newStatus = FeedbackStatus.valueOf(form["status"])

        val feedback = GetFeedback(feedbackId).query() ?: return ServerResponse.notFound().build()

        // Only admin or creator can update status
        val principal = SecurityContext.current()
        if (principal.userId != feedback.createdBy && !principal.isManager(tenantId.key)) {
            throw FeedbackAccessDeniedException(tenantId.key, feedbackId.key)
        }

        UpdateFeedbackStatus(id = feedbackId, status = newStatus).execute()

        // Reload and return updated detail
        val updated = GetFeedback(feedbackId).query()!!
        val comments = GetFeedbackComments(feedbackId).query()
        val assets = ListFeedbackAssets(feedbackId).query()

        return request.htmx {
            fragment("feedback/detail", "feedback-content") {
                "tenantId" to tenantId.key
                "feedback" to updated
                "comments" to comments
                "assets" to assets
                "statuses" to FeedbackStatus.entries
                "isManager" to principal.isManager(tenantId.key)
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/feedback/${feedbackId.key}") }
        }
    }

    fun addComment(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val feedbackId = request.feedbackId(tenantId) ?: return ServerResponse.badRequest().build()

        val form = request.form {
            field("body") { required() }
        }

        if (form.hasErrors()) return ServerResponse.badRequest().build()

        val feedback = GetFeedback(feedbackId).query() ?: return ServerResponse.notFound().build()

        val principal = SecurityContext.current()
        if (principal.userId != feedback.createdBy && !principal.isManager(tenantId.key)) {
            throw FeedbackAccessDeniedException(tenantId.key, feedbackId.key)
        }

        val commentId = FeedbackCommentId(FeedbackCommentKey.generate(), feedbackId)

        AddFeedbackComment(
            id = commentId,
            body = form["body"],
            authorName = principal.displayName,
            authorEmail = principal.email,
        ).execute()

        // Return updated comments list
        val comments = GetFeedbackComments(feedbackId).query()

        return request.htmx {
            fragment("feedback/detail", "comments-section") {
                "tenantId" to tenantId.key
                "feedback" to feedback
                "comments" to comments
                "isManager" to principal.isManager(tenantId.key)
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/feedback/${feedbackId.key}") }
        }
    }

    fun submitForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()

        return request.htmx {
            fragment("feedback/submit-form") {
                "tenantId" to tenantId.key
                "categories" to FeedbackCategory.entries
                "priorities" to FeedbackPriority.entries
                "appVersion" to (buildProperties?.version ?: "dev")
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/feedback") }
        }
    }

    fun feedbackSync(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val config = GetFeedbackSyncConfig(tenantId.key).query()

        val formData = mutableMapOf<String, String>()
        if (config != null) {
            formData["enabled"] = if (config.enabled) "on" else ""
            formData["providerType"] = config.providerType.name
            if (config.enabled && config.providerType == SyncProviderType.GITHUB) {
                val github = objectMapper.readValue(config.settings, GitHubSyncSettings::class.java)
                formData["personalAccessToken"] = maskToken(github.personalAccessToken)
                formData["repoOwner"] = github.repoOwner
                formData["repoName"] = github.repoName
                formData["label"] = github.label
            }
            config.lastPolledAt?.let { formData["lastPolledAt"] = it.toString() }
        }

        return ServerResponse.ok().page("feedback/sync") {
            "pageTitle" to "Feedback Sync - Epistola"
            "tenantId" to tenantId.key
            "activeNavSection" to "feedback"
            "formData" to formData
        }
    }

    fun saveFeedbackSync(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val enabled = request.param("enabled").isPresent

        val form = request.form {
            field("providerType") { required() }
            if (enabled) {
                field("repoOwner") {
                    required()
                    maxLength(100)
                }
                field("repoName") {
                    required()
                    maxLength(100)
                }
                field("personalAccessToken") {
                    required()
                    maxLength(500)
                }
                field("label") { maxLength(100) }
            }
        }

        if (form.hasErrors()) {
            val combinedFormData = form.formData + Pair("enabled", if (enabled) "on" else "")
            return ServerResponse.ok().page("feedback/sync") {
                "pageTitle" to "Feedback Sync - Epistola"
                "tenantId" to tenantId.key
                "activeNavSection" to "feedback"
                "formData" to combinedFormData
                "errors" to form.errors
            }
        }

        val providerType = SyncProviderType.valueOf(form["providerType"])

        val submittedToken = form["personalAccessToken"]
        val resolvedToken = if (enabled && isMaskedToken(submittedToken)) {
            val existing = GetFeedbackSyncConfig(tenantId.key).query()
            if (existing != null && existing.providerType == SyncProviderType.GITHUB) {
                val existingSettings = objectMapper.readValue(existing.settings, GitHubSyncSettings::class.java)
                existingSettings.personalAccessToken
            } else {
                submittedToken
            }
        } else {
            submittedToken
        }

        val settingsJson = if (enabled) {
            objectMapper.writeValueAsString(
                GitHubSyncSettings(
                    personalAccessToken = resolvedToken,
                    repoOwner = form["repoOwner"],
                    repoName = form["repoName"],
                    label = form["label"].ifBlank { "etk-${tenantId.key}" },
                ),
            )
        } else {
            "{}"
        }

        SaveFeedbackSyncConfig(
            tenantKey = tenantId.key,
            enabled = enabled,
            providerType = providerType,
            settings = settingsJson,
        ).execute()

        return ServerResponse.status(303)
            .header("Location", "/tenants/${tenantId.key}/feedback/sync?saved=true")
            .build()
    }

    fun assetContent(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val feedbackId = request.feedbackId(tenantId) ?: return ServerResponse.badRequest().build()

        val assetUuid = runCatching {
            java.util.UUID.fromString(request.pathVariable("assetId"))
        }.getOrNull() ?: return ServerResponse.badRequest().build()

        val assetId = FeedbackAssetId(FeedbackAssetKey.of(assetUuid), feedbackId)

        // Access control: admin or creator only
        val feedback = GetFeedback(feedbackId).query() ?: return ServerResponse.notFound().build()
        val principal = SecurityContext.current()
        if (principal.userId != feedback.createdBy && !principal.isManager(tenantId.key)) {
            throw FeedbackAccessDeniedException(tenantId.key, feedbackId.key)
        }

        val content = GetFeedbackAssetContent(assetId).query() ?: return ServerResponse.notFound().build()

        return ServerResponse.ok()
            .contentType(MediaType.parseMediaType(content.contentType))
            .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
            .body(content.content)
    }

    companion object {
        fun maskToken(token: String): String {
            val underscoreIdx = token.indexOf('_')
            return if (underscoreIdx > 0 && token.length > underscoreIdx + 5) {
                val prefix = token.substring(0, underscoreIdx + 1)
                val lastFour = token.takeLast(4)
                "$prefix****$lastFour"
            } else if (token.length > 8) {
                "${token.take(4)}****${token.takeLast(4)}"
            } else {
                "****"
            }
        }

        fun isMaskedToken(value: String): Boolean = "****" in value
    }
}
