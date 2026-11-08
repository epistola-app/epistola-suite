// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.ui

import app.epistola.suite.common.ids.SupportRequestAssetId
import app.epistola.suite.common.ids.SupportRequestAssetKey
import app.epistola.suite.common.ids.SupportRequestCommentId
import app.epistola.suite.common.ids.SupportRequestCommentKey
import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.common.ids.SupportRequestKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.htmx.HxSwap
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.supportRequestId
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.support.requests.SupportRequestAccessDeniedException
import app.epistola.suite.support.requests.SupportRequestCategory
import app.epistola.suite.support.requests.SupportRequestLogSource
import app.epistola.suite.support.requests.SupportRequestPriority
import app.epistola.suite.support.requests.SupportRequestStatus
import app.epistola.suite.support.requests.commands.AddSupportRequestAsset
import app.epistola.suite.support.requests.commands.AddSupportRequestComment
import app.epistola.suite.support.requests.commands.CreateSupportRequest
import app.epistola.suite.support.requests.commands.UpdateSupportRequestStatus
import app.epistola.suite.support.requests.queries.GetSupportRequest
import app.epistola.suite.support.requests.queries.GetSupportRequestAssetContent
import app.epistola.suite.support.requests.queries.GetSupportRequestComments
import app.epistola.suite.support.requests.queries.ListSupportRequest
import app.epistola.suite.support.requests.queries.ListSupportRequestAssets
import app.epistola.suite.tenants.queries.GetTenant
import org.slf4j.LoggerFactory
import org.springframework.boot.info.BuildProperties
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.util.concurrent.TimeUnit

/**
 * UI handler for the feedback feature. The feedback UI is freely usable; sending feedback to
 * Epistola support needs the support connection (`epistola.support.enabled`) and the tenant's
 * consent — without either, feedback stays local. Templates live in this module under
 * `templates/feedback` and merge onto the host app's classpath at runtime, reusing the app's
 * `layout/shell` chrome. Gated for visibility only by the `support-requests` feature toggle.
 */
@Component
class SupportRequestHandler(
    private val buildProperties: BuildProperties?,
    private val logSource: SupportRequestLogSource,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun list(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val tenant = GetTenant(tenantId.key).query() ?: return ServerResponse.notFound().build()

        val statusFilter = request.param("status").orElse(null)
            ?.let { runCatching { SupportRequestStatus.valueOf(it) }.getOrNull() }
        val categoryFilter = request.param("category").orElse(null)
            ?.let { runCatching { SupportRequestCategory.valueOf(it) }.getOrNull() }

        val supportRequestItems = ListSupportRequest(
            tenantKey = tenantId.key,
            status = statusFilter,
            category = categoryFilter,
        ).query()

        return ServerResponse.ok().page("support-requests/list") {
            "pageTitle" to "Support requests - Epistola"
            "tenant" to tenant
            "tenantId" to tenantId.key
            "supportRequestItems" to supportRequestItems
            "activeNavSection" to "requests"
            "statuses" to SupportRequestStatus.entries
            "categories" to SupportRequestCategory.entries
            "selectedStatus" to statusFilter
            "selectedCategory" to categoryFilter
        }
    }

    fun search(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val sourceUrl = request.param("url").orElse(null)

        val statusFilter = request.param("status").orElse(null)
            ?.let { runCatching { SupportRequestStatus.valueOf(it) }.getOrNull() }
        val categoryFilter = request.param("category").orElse(null)
            ?.let { runCatching { SupportRequestCategory.valueOf(it) }.getOrNull() }

        val supportRequestItems = ListSupportRequest(
            tenantKey = tenantId.key,
            status = statusFilter,
            category = categoryFilter,
            sourceUrl = sourceUrl,
        ).query()

        if (sourceUrl != null) {
            return request.htmx {
                fragment("support-requests/page-issues", "content") {
                    "tenantId" to tenantId.key
                    "supportRequestItems" to supportRequestItems
                }
                onFullPage { redirect("/tenants/${tenantId.key}/support/requests") }
            }
        }

        return request.htmx {
            fragment("support-requests/list", "rows") {
                "tenantId" to tenantId.key
                "supportRequestItems" to supportRequestItems
            }
            onFullPage { redirect("/tenants/${tenantId.key}/support/requests") }
        }
    }

    fun detail(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val supportRequestId = request.supportRequestId(tenantId) ?: return ServerResponse.badRequest().build()

        val supportRequest = GetSupportRequest(supportRequestId).query() ?: return ServerResponse.notFound().build()

        // Access control: admin or creator only
        val principal = SecurityContext.current()
        if (principal.userId != supportRequest.createdBy && !principal.hasPermission(tenantId.key, Permission.SUPPORT_REQUEST_HANDLE)) {
            throw SupportRequestAccessDeniedException(tenantId.key, supportRequestId.key)
        }

        val comments = GetSupportRequestComments(supportRequestId).query()
        val tenant = GetTenant(tenantId.key).query()
        val assets = ListSupportRequestAssets(supportRequestId).query()

        return ServerResponse.ok().page("support-requests/detail") {
            "pageTitle" to "${supportRequest.title} - Support requests - Epistola"
            "tenant" to tenant
            "tenantId" to tenantId.key
            "supportRequest" to supportRequest
            "comments" to comments
            "assets" to assets
            "activeNavSection" to "requests"
            "statuses" to SupportRequestStatus.entries
            "mayManageSupportRequest" to principal.hasPermission(tenantId.key, Permission.SUPPORT_REQUEST_HANDLE)
        }
    }

    /** The trace ids the form collected (a JSON array), at most [MAX_TRACE_IDS]; anything unreadable is none. */
    private fun traceIds(raw: String?): List<String> = raw?.let { text ->
        TRACE_ID_IN_JSON.findAll(text).map { it.groupValues[1] }.distinct().take(MAX_TRACE_IDS).toList()
    }.orEmpty()

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()

        val form = request.form {
            field("title") { required() }
            field("description") { required() }
            field("category") { required() }
            field("priority") { required() }
        }

        if (form.hasErrors()) {
            // The form again, into the dialog it came from, with the errors by their fields; a 422
            // that names its swap, so HTMX swaps it (a whole page here would nest the app in the dialog).
            return request.htmx {
                fragment("support-requests/submit-form", "content") {
                    "tenantId" to tenantId.key
                    "formData" to form.formData
                    "errors" to form.errors
                    "categories" to SupportRequestCategory.entries
                    "priorities" to SupportRequestPriority.entries
                    "appVersion" to (buildProperties?.version ?: "dev")
                }
                reswap(HxSwap.INNER_HTML)
                status(422)
                onFullPage { redirect("/tenants/${tenantId.key}/support/requests") }
            }
        }

        val principal = SecurityContext.current()
        val category = SupportRequestCategory.valueOf(form["category"])
        val priority = SupportRequestPriority.valueOf(form["priority"])
        val sourceUrl = form.formData["sourceUrl"]
        val consoleLogs = form.formData["consoleLogs"]
        val metadata = form.formData["metadata"]
        val screenshotData = form.formData["screenshotData"]
        // The server log of the submitter's recent requests, only when they chose to attach it.
        val serverLogs = if (form.formData["attachServerLogs"] == "true") {
            runCatching { logSource.excerpt(tenantId.key, traceIds(form.formData["traceIds"])) }
                .onFailure { log.warn("The server log could not be attached to feedback: {}", it.javaClass.simpleName) }
                .getOrNull()
        } else {
            null
        }

        val supportRequestId = SupportRequestId(SupportRequestKey.generate(), TenantId(tenantId.key))

        CreateSupportRequest(
            id = supportRequestId,
            title = form["title"],
            description = form["description"],
            category = category,
            priority = priority,
            sourceUrl = sourceUrl,
            consoleLogs = consoleLogs,
            metadata = metadata,
            createdBy = principal.userId,
            serverLogs = serverLogs,
        ).execute()

        // Store screenshot as feedback asset if provided
        screenshotData?.let { dataUrl ->
            val assetId = SupportRequestAssetId(SupportRequestAssetKey.generate(), supportRequestId)
            AddSupportRequestAsset.fromDataUrl(assetId, dataUrl)?.let { command ->
                try {
                    command.execute()
                } catch (e: Exception) {
                    log.warn("Failed to store feedback screenshot: {}", e.message)
                }
            }
        }

        return request.htmx {
            fragment("support-requests/submit-success") {}
            onFullPage { redirect("/tenants/${tenantId.key}/support/requests") }
        }
    }

    fun updateStatus(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val supportRequestId = request.supportRequestId(tenantId) ?: return ServerResponse.badRequest().build()

        val form = request.form {
            field("status") { required() }
        }

        if (form.hasErrors()) return ServerResponse.badRequest().build()

        val newStatus = SupportRequestStatus.valueOf(form["status"])

        val supportRequest = GetSupportRequest(supportRequestId).query() ?: return ServerResponse.notFound().build()

        // Only admin or creator can update status
        val principal = SecurityContext.current()
        if (principal.userId != supportRequest.createdBy && !principal.hasPermission(tenantId.key, Permission.SUPPORT_REQUEST_HANDLE)) {
            throw SupportRequestAccessDeniedException(tenantId.key, supportRequestId.key)
        }

        UpdateSupportRequestStatus(id = supportRequestId, status = newStatus).execute()

        // Reload and return updated detail
        val updated = GetSupportRequest(supportRequestId).query()!!
        val comments = GetSupportRequestComments(supportRequestId).query()
        val assets = ListSupportRequestAssets(supportRequestId).query()

        return request.htmx {
            fragment("support-requests/detail", "support-request-content") {
                "tenantId" to tenantId.key
                "supportRequest" to updated
                "comments" to comments
                "assets" to assets
                "statuses" to SupportRequestStatus.entries
                "mayManageSupportRequest" to principal.hasPermission(tenantId.key, Permission.SUPPORT_REQUEST_HANDLE)
            }
            onFullPage { redirect("/tenants/${tenantId.key}/support/requests/${supportRequestId.key}") }
        }
    }

    fun addComment(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val supportRequestId = request.supportRequestId(tenantId) ?: return ServerResponse.badRequest().build()

        val form = request.form {
            field("body") { required() }
        }

        if (form.hasErrors()) return request.htmx { globalFormError("add-comment-error", "Write a comment first.") }

        val supportRequest = GetSupportRequest(supportRequestId).query() ?: return ServerResponse.notFound().build()

        val principal = SecurityContext.current()
        if (principal.userId != supportRequest.createdBy && !principal.hasPermission(tenantId.key, Permission.SUPPORT_REQUEST_HANDLE)) {
            throw SupportRequestAccessDeniedException(tenantId.key, supportRequestId.key)
        }

        val commentId = SupportRequestCommentId(SupportRequestCommentKey.generate(), supportRequestId)

        AddSupportRequestComment(
            id = commentId,
            body = form["body"],
            authorName = principal.displayName,
            authorEmail = principal.email,
            authorId = principal.userId,
        ).execute()

        // Return updated comments list
        val comments = GetSupportRequestComments(supportRequestId).query()

        return request.htmx {
            fragment("support-requests/detail", "comments-section") {
                "tenantId" to tenantId.key
                "supportRequest" to supportRequest
                "comments" to comments
                "mayManageSupportRequest" to principal.hasPermission(tenantId.key, Permission.SUPPORT_REQUEST_HANDLE)
            }
            onFullPage { redirect("/tenants/${tenantId.key}/support/requests/${supportRequestId.key}") }
        }
    }

    fun submitForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()

        return request.htmx {
            fragment("support-requests/submit-form") {
                "tenantId" to tenantId.key
                "categories" to SupportRequestCategory.entries
                "priorities" to SupportRequestPriority.entries
                "appVersion" to (buildProperties?.version ?: "dev")
            }
            onFullPage { redirect("/tenants/${tenantId.key}/support/requests") }
        }
    }

    fun assetContent(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val supportRequestId = request.supportRequestId(tenantId) ?: return ServerResponse.badRequest().build()

        val assetUuid = runCatching {
            java.util.UUID.fromString(request.pathVariable("assetId"))
        }.getOrNull() ?: return ServerResponse.badRequest().build()

        val assetId = SupportRequestAssetId(SupportRequestAssetKey.of(assetUuid), supportRequestId)

        // Access control: admin or creator only
        val supportRequest = GetSupportRequest(supportRequestId).query() ?: return ServerResponse.notFound().build()
        val principal = SecurityContext.current()
        if (principal.userId != supportRequest.createdBy && !principal.hasPermission(tenantId.key, Permission.SUPPORT_REQUEST_HANDLE)) {
            throw SupportRequestAccessDeniedException(tenantId.key, supportRequestId.key)
        }

        val content = GetSupportRequestAssetContent(assetId).query() ?: return ServerResponse.notFound().build()

        return ServerResponse.ok()
            .contentType(MediaType.parseMediaType(content.contentType))
            .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
            .body(content.content)
    }
}

/** A W3C trace id in the form's JSON array. */
private val TRACE_ID_IN_JSON = Regex("\"([0-9a-f]{32})\"")

/** The most recent requests a feedback item's server log covers. */
private const val MAX_TRACE_IDS = 20
