package app.epistola.suite.handlers

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.FeedbackCommentId
import app.epistola.suite.common.ids.FeedbackCommentKey
import app.epistola.suite.common.ids.FeedbackId
import app.epistola.suite.common.ids.FeedbackKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.feedback.FeedbackAccessDeniedException
import app.epistola.suite.feedback.FeedbackCategory
import app.epistola.suite.feedback.FeedbackPriority
import app.epistola.suite.feedback.FeedbackStatus
import app.epistola.suite.feedback.commands.AddFeedbackComment
import app.epistola.suite.feedback.commands.CreateFeedback
import app.epistola.suite.feedback.commands.UpdateFeedbackStatus
import app.epistola.suite.feedback.queries.GetFeedback
import app.epistola.suite.feedback.queries.GetFeedbackComments
import app.epistola.suite.feedback.queries.ListFeedback
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
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.util.Base64

@Component
class FeedbackHandler(
    private val buildProperties: BuildProperties?,
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

        val statusFilter = request.param("status").orElse(null)
            ?.let { runCatching { FeedbackStatus.valueOf(it) }.getOrNull() }
        val categoryFilter = request.param("category").orElse(null)
            ?.let { runCatching { FeedbackCategory.valueOf(it) }.getOrNull() }

        val feedbackItems = ListFeedback(
            tenantKey = tenantId.key,
            status = statusFilter,
            category = categoryFilter,
        ).query()

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
        if (principal.userId != feedback.createdBy && !principal.isAdmin(tenantId.key)) {
            throw FeedbackAccessDeniedException(tenantId.key, feedbackId.key)
        }

        val comments = GetFeedbackComments(feedbackId).query()
        val tenant = GetTenant(tenantId.key).query()

        return ServerResponse.ok().page("feedback/detail") {
            "pageTitle" to "${feedback.title} - Feedback - Epistola"
            "tenant" to tenant
            "tenantId" to tenantId.key
            "feedback" to feedback
            "comments" to comments
            "activeNavSection" to "feedback"
            "statuses" to FeedbackStatus.entries
            "isAdmin" to principal.isAdmin(tenantId.key)
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

        // Upload screenshot if provided (base64 data URL)
        val screenshotKey = screenshotData
            ?.takeIf { it.startsWith("data:image/") }
            ?.let { uploadScreenshot(tenantId.key, it) }

        val feedbackId = FeedbackId(FeedbackKey.generate(), TenantId(tenantId.key))

        CreateFeedback(
            id = feedbackId,
            title = form["title"],
            description = form["description"],
            category = category,
            priority = priority,
            sourceUrl = sourceUrl,
            screenshotKey = screenshotKey,
            consoleLogs = consoleLogs,
            metadata = metadata,
            createdBy = principal.userId,
        ).execute()

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
        if (principal.userId != feedback.createdBy && !principal.isAdmin(tenantId.key)) {
            throw FeedbackAccessDeniedException(tenantId.key, feedbackId.key)
        }

        UpdateFeedbackStatus(id = feedbackId, status = newStatus).execute()

        // Reload and return updated detail
        val updated = GetFeedback(feedbackId).query()!!
        val comments = GetFeedbackComments(feedbackId).query()

        return request.htmx {
            fragment("feedback/detail", "feedback-content") {
                "tenantId" to tenantId.key
                "feedback" to updated
                "comments" to comments
                "statuses" to FeedbackStatus.entries
                "isAdmin" to principal.isAdmin(tenantId.key)
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
        if (principal.userId != feedback.createdBy && !principal.isAdmin(tenantId.key)) {
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
                "isAdmin" to principal.isAdmin(tenantId.key)
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

    /**
     * Decode a base64 data URL and upload it as an asset.
     * Format: data:image/png;base64,iVBOR...
     */
    private fun uploadScreenshot(tenantKey: app.epistola.suite.common.ids.TenantKey, dataUrl: String): AssetKey? {
        return try {
            val parts = dataUrl.split(",", limit = 2)
            if (parts.size != 2) return null

            val mimeType = parts[0].removePrefix("data:").removeSuffix(";base64")
            val mediaType = AssetMediaType.fromMimeType(mimeType)
            val imageBytes = Base64.getDecoder().decode(parts[1])

            val asset = UploadAsset(
                tenantId = tenantKey,
                name = "feedback-screenshot.${mediaType.name.lowercase()}",
                mediaType = mediaType,
                content = imageBytes,
                width = null,
                height = null,
            ).execute()

            asset.id
        } catch (e: Exception) {
            log.warn("Failed to upload feedback screenshot: {}", e.message)
            null
        }
    }
}
