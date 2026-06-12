package app.epistola.suite.handlers

import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.queryParam
import app.epistola.suite.htmx.queryParamInt
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.logs.ApplicationLogEntry
import app.epistola.suite.logs.queries.ListApplicationLogs
import app.epistola.suite.mediator.query
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * Tenant-scoped Logs viewer (Operations area). Shows the tenant's own log rows
 * plus system rows with no tenant, newest first, filterable by level and logger
 * and paginated. Reads go through [ListApplicationLogs] (permission-gated); no
 * JDBI in the handler.
 */
@Component
class LogsHandler {

    private companion object {
        const val PAGE_SIZE = 100
        val LEVELS = listOf("ERROR", "WARN", "INFO", "DEBUG", "TRACE")
    }

    fun list(request: ServerRequest): ServerResponse {
        val view = load(request)
        return ServerResponse.ok().page("logs/list") {
            "pageTitle" to "Logs - Epistola"
            "tenantId" to view.tenantKey
            "logs" to view.logs
            "levels" to LEVELS
            "selectedLevel" to view.level
            "loggerFilter" to view.logger
            "page" to view.page
            "hasMore" to view.hasMore
        }
    }

    fun search(request: ServerRequest): ServerResponse {
        val view = load(request)
        return request.htmx {
            fragment("logs/list", "results") {
                "tenantId" to view.tenantKey
                "logs" to view.logs
                "selectedLevel" to view.level
                "loggerFilter" to view.logger
                "page" to view.page
                "hasMore" to view.hasMore
            }
            onNonHtmx { redirect("/tenants/${view.tenantKey}/logs") }
        }
    }

    private fun load(request: ServerRequest): LogsView {
        val tenantId = request.tenantId()
        val level = request.queryParam("level")?.takeIf { it.isNotBlank() }
        val logger = request.queryParam("logger")?.takeIf { it.isNotBlank() }
        val page = request.queryParamInt("page", 0).coerceAtLeast(0)

        val logs = ListApplicationLogs(
            tenantId = tenantId.key,
            level = level,
            logger = logger,
            limit = PAGE_SIZE,
            offset = page * PAGE_SIZE,
        ).query()

        return LogsView(
            tenantKey = tenantId.key.value,
            logs = logs,
            level = level,
            logger = logger,
            page = page,
            hasMore = logs.size == PAGE_SIZE,
        )
    }

    private data class LogsView(
        val tenantKey: String,
        val logs: List<ApplicationLogEntry>,
        val level: String?,
        val logger: String?,
        val page: Int,
        val hasMore: Boolean,
    )
}
