// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.ui

import app.epistola.suite.common.PageRequest
import app.epistola.suite.common.ids.SupportRequestAssetKey
import app.epistola.suite.common.ids.SupportRequestKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.redirect
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.requests.SupportRequestPriority
import app.epistola.suite.support.requests.SupportRequestStatus
import app.epistola.suite.support.requests.desk.AddSupportDeskMessage
import app.epistola.suite.support.requests.desk.EscalateSupportRequest
import app.epistola.suite.support.requests.desk.GetSupportDeskAssetContent
import app.epistola.suite.support.requests.desk.GetSupportInboxRequest
import app.epistola.suite.support.requests.desk.GetSupportRequestHandling
import app.epistola.suite.support.requests.desk.ListSupportInbox
import app.epistola.suite.support.requests.desk.ResetSupportRequestHandling
import app.epistola.suite.support.requests.desk.SetSupportDeskPriority
import app.epistola.suite.support.requests.desk.SetSupportDeskStatus
import app.epistola.suite.support.requests.desk.SetSupportRequestHandling
import app.epistola.suite.support.requests.desk.SupportInboxHandler
import app.epistola.suite.support.requests.desk.SupportRequestHandledElsewhereException
import app.epistola.suite.support.requests.desk.SupportRequestNotEscalatedException
import app.epistola.suite.tenants.queries.ListTenants
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router
import java.util.UUID

/**
 * `/platform/support-requests` — the support desk: the installation's support requests across
 * tenants (ADR 0053 §3–4), who handles new ones, and per request the conversation, internal notes,
 * status, priority and passing it on. A platform page; the mediator enforces the desk's role on every
 * message.
 */
@Configuration
class SupportDeskRoutes(private val handler: SupportDeskHandler) {
    @Bean
    fun supportDeskRouterFunction(): RouterFunction<ServerResponse> = router {
        "/platform/support-requests".nest {
            GET("", handler::inbox)
            POST("/handling", handler::saveHandling)
            POST("/handling/reset", handler::resetHandling)
            GET("/{tenantKey}/{requestId}", handler::detail)
            POST("/{tenantKey}/{requestId}/messages", handler::addMessage)
            POST("/{tenantKey}/{requestId}/status", handler::setStatus)
            POST("/{tenantKey}/{requestId}/priority", handler::setPriority)
            POST("/{tenantKey}/{requestId}/escalate", handler::escalate)
            GET("/{tenantKey}/{requestId}/assets/{assetId}", handler::assetContent)
        }
    }
}

@Component
class SupportDeskHandler {
    fun inbox(request: ServerRequest): ServerResponse {
        val status = request.param("status").orElse(null)?.let { runCatching { SupportRequestStatus.valueOf(it) }.getOrNull() }
        val priority = request.param("priority").orElse(null)?.let { runCatching { SupportRequestPriority.valueOf(it) }.getOrNull() }
        val tenant = request.param("tenant").orElse(null)?.takeIf { it.isNotBlank() }?.let { runCatching { TenantKey.of(it) }.getOrNull() }
        val handler = request.param("handler").orElse(null)?.let { runCatching { SupportInboxHandler.valueOf(it) }.getOrNull() } ?: SupportInboxHandler.ALL
        val offset = request.param("offset").orElse("0").toIntOrNull()?.coerceAtLeast(0) ?: 0
        val page = ListSupportInbox(status, tenant, priority, handler, PageRequest(ListSupportInbox.PAGE_SIZE, offset)).query()
        val filtered = status != null || priority != null || tenant != null || handler != SupportInboxHandler.ALL
        return ServerResponse.ok().page("support-requests/desk") {
            "pageTitle" to "Support requests - Epistola"
            "platformPage" to "Support requests"
            "inboxPage" to page
            "handling" to GetSupportRequestHandling().query()
            "tenants" to ListTenants().query()
            "statuses" to SupportRequestStatus.entries
            "priorities" to SupportRequestPriority.entries
            "handlers" to SupportInboxHandler.entries
            "selectedStatus" to status
            "selectedPriority" to priority
            "selectedTenant" to tenant?.value
            "selectedHandler" to handler
            "filtered" to filtered
            "offset" to offset
            "pageSize" to ListSupportInbox.PAGE_SIZE
            "previousOffset" to (offset - ListSupportInbox.PAGE_SIZE).coerceAtLeast(0)
            "nextOffset" to offset + ListSupportInbox.PAGE_SIZE
            "hasNext" to (offset + ListSupportInbox.PAGE_SIZE < page.total)
            "saved" to request.param("saved").isPresent
        }
    }

    fun saveHandling(request: ServerRequest): ServerResponse {
        val connection = request.param("connection").orElse("").takeIf { it.isNotBlank() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        SetSupportRequestHandling(connection).execute()
        return redirect("/platform/support-requests?saved=true")
    }

    fun resetHandling(request: ServerRequest): ServerResponse {
        ResetSupportRequestHandling().execute()
        return redirect("/platform/support-requests?saved=true")
    }

    fun detail(request: ServerRequest): ServerResponse = detailPage(request, error = null)

    fun addMessage(request: ServerRequest): ServerResponse {
        val (tenant, key) = ids(request) ?: return ServerResponse.badRequest().build()
        val body = request.param("body").orElse("").trim()
        if (body.isEmpty()) return detailPage(request, "Write a message first.")
        AddSupportDeskMessage(tenant, key, body, note = request.param("note").orElse("") == "true").execute()
        return redirect(detailPath(tenant, key))
    }

    fun setStatus(request: ServerRequest): ServerResponse {
        val (tenant, key) = ids(request) ?: return ServerResponse.badRequest().build()
        val status = request.param("status").orElse(null)?.let { runCatching { SupportRequestStatus.valueOf(it) }.getOrNull() }
            ?: return ServerResponse.badRequest().build()
        return handledHere(request) { SetSupportDeskStatus(tenant, key, status).execute() }
    }

    fun setPriority(request: ServerRequest): ServerResponse {
        val (tenant, key) = ids(request) ?: return ServerResponse.badRequest().build()
        val priority = request.param("priority").orElse(null)?.let { runCatching { SupportRequestPriority.valueOf(it) }.getOrNull() }
            ?: return ServerResponse.badRequest().build()
        return handledHere(request) { SetSupportDeskPriority(tenant, key, priority).execute() }
    }

    fun escalate(request: ServerRequest): ServerResponse {
        val (tenant, key) = ids(request) ?: return ServerResponse.badRequest().build()
        val connection = request.param("connection").orElse("").let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return ServerResponse.badRequest().build()
        return try {
            EscalateSupportRequest(tenant, key, connection).execute()
            redirect(detailPath(tenant, key))
        } catch (e: SupportRequestNotEscalatedException) {
            detailPage(request, e.reason)
        } catch (_: SupportRequestHandledElsewhereException) {
            detailPage(request, "A support connection handles this request already.")
        }
    }

    fun assetContent(request: ServerRequest): ServerResponse {
        val (tenant, key) = ids(request) ?: return ServerResponse.badRequest().build()
        val asset = runCatching { SupportRequestAssetKey.of(request.pathVariable("assetId")) }.getOrNull() ?: return ServerResponse.badRequest().build()
        val content = GetSupportDeskAssetContent(tenant, key, asset).query() ?: return ServerResponse.notFound().build()
        return ServerResponse.ok()
            .contentType(MediaType.parseMediaType(content.contentType))
            .cacheControl(CacheControl.noStore())
            .body(content.content)
    }

    private fun handledHere(request: ServerRequest, action: () -> Unit): ServerResponse {
        val (tenant, key) = ids(request) ?: return ServerResponse.badRequest().build()
        return try {
            action()
            redirect(detailPath(tenant, key))
        } catch (_: SupportRequestHandledElsewhereException) {
            detailPage(request, "A support connection handles this request: it sets the status and the priority.")
        }
    }

    private fun detailPage(request: ServerRequest, error: String?): ServerResponse {
        val (tenant, key) = ids(request) ?: return ServerResponse.badRequest().build()
        val view = GetSupportInboxRequest(tenant, key).query() ?: return ServerResponse.notFound().build()
        val status = if (error == null) 200 else 422
        return ServerResponse.status(status).page("support-requests/desk-detail") {
            "pageTitle" to "${view.request.title} - Support requests - Epistola"
            "platformPage" to "Support requests"
            "view" to view
            "statuses" to SupportRequestStatus.entries
            "priorities" to SupportRequestPriority.entries
            "error" to error
        }
    }

    private fun ids(request: ServerRequest): Pair<TenantKey, SupportRequestKey>? {
        val tenant = runCatching { TenantKey.of(request.pathVariable("tenantKey")) }.getOrNull() ?: return null
        val key = runCatching { SupportRequestKey.of(request.pathVariable("requestId")) }.getOrNull() ?: return null
        return tenant to key
    }

    private fun detailPath(tenant: TenantKey, key: SupportRequestKey) = "/platform/support-requests/${tenant.value}/${key.value}"
}
