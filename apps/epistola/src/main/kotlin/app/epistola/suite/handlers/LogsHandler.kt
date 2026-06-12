package app.epistola.suite.handlers

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.queryParam
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.logs.ApplicationLogEntry
import app.epistola.suite.logs.queries.ListApplicationLogs
import app.epistola.suite.logs.queries.LogPageDirection
import app.epistola.suite.mediator.query
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Tenant-scoped Logs viewer (Operations area). Shows the tenant's own log rows
 * plus system rows with no tenant, newest first. Filterable by level, logger,
 * a free-text message search, and a date/time range. The default view shows the
 * newest [PAGE_SIZE] rows; "Load newer" / "Load older" page incrementally via
 * keyset cursors (prepending / appending rows) so the table grows in place
 * without offset drift. Reads go through [ListApplicationLogs] (permission-gated);
 * no JDBI in the handler.
 */
@Component
class LogsHandler {

    private companion object {
        const val PAGE_SIZE = 20
        val LEVELS = listOf("ERROR", "WARN", "INFO", "DEBUG", "TRACE")
    }

    /** Full page: filter form + the newest [PAGE_SIZE] rows. */
    fun list(request: ServerRequest): ServerResponse {
        val filters = filters(request)
        val page = query(filters, LogPageDirection.OLDER, cursor = null)
        return ServerResponse.ok().page("logs/list") {
            "pageTitle" to "Logs - Epistola"
            "tenantId" to filters.tenantKey
            "levels" to LEVELS
            "selectedLevel" to filters.level
            "searchQuery" to filters.search
            "loggerFilter" to filters.logger
            "fromValue" to filters.fromRaw
            "toValue" to filters.toRaw
            applyResultsModel(page)
        }
    }

    /** Filter change: re-render the results region with a fresh newest-first page. */
    fun search(request: ServerRequest): ServerResponse {
        val filters = filters(request)
        val page = query(filters, LogPageDirection.OLDER, cursor = null)
        return request.htmx {
            fragment("logs/list", "results") {
                "tenantId" to filters.tenantKey
                applyResultsModel(page)
            }
            onNonHtmx { redirect("/tenants/${filters.tenantKey}/logs") }
        }
    }

    /** Load older: append rows below the current oldest, plus a refreshed "older" sentinel. */
    fun older(request: ServerRequest): ServerResponse {
        val filters = filters(request)
        val cursor = cursor(request) ?: return ServerResponse.badRequest().build()
        val page = query(filters, LogPageDirection.OLDER, cursor)
        return request.htmx {
            fragment("logs/list", "older-rows") {
                "tenantId" to filters.tenantKey
                applyResultsModel(page)
            }
            onNonHtmx { redirect("/tenants/${filters.tenantKey}/logs") }
        }
    }

    /** Load newer: prepend rows above the current newest, keeping the "newer" sentinel. */
    fun newer(request: ServerRequest): ServerResponse {
        val filters = filters(request)
        val cursor = cursor(request) ?: return ServerResponse.badRequest().build()
        val page = query(filters, LogPageDirection.NEWER, cursor)
        return request.htmx {
            fragment("logs/list", "newer-rows") {
                "tenantId" to filters.tenantKey
                // When nothing newer arrived yet, keep the original cursor so the sentinel still works.
                "newerCursorTs" to (page.newerCursorTs ?: cursor.ts.toString())
                "newerCursorId" to (page.newerCursorId ?: cursor.id.toString())
                "logs" to page.logs
            }
            onNonHtmx { redirect("/tenants/${filters.tenantKey}/logs") }
        }
    }

    // -- internals -----------------------------------------------------------

    private fun query(filters: Filters, direction: LogPageDirection, cursor: Cursor?): Page {
        val logs = ListApplicationLogs(
            tenantId = filters.tenantId.key,
            level = filters.level,
            logger = filters.logger,
            search = filters.search,
            from = filters.from,
            to = filters.to,
            limit = PAGE_SIZE,
            direction = direction,
            cursorOccurredAt = cursor?.ts,
            cursorId = cursor?.id,
        ).query()
        return Page(logs, hasMoreOlder = direction == LogPageDirection.OLDER && logs.size == PAGE_SIZE)
    }

    private fun filters(request: ServerRequest): Filters {
        val tenantId = request.tenantId()
        val fromRaw = request.queryParam("from")?.takeIf { it.isNotBlank() }
        val toRaw = request.queryParam("to")?.takeIf { it.isNotBlank() }
        val zone = browserZone(request)
        return Filters(
            tenantId = tenantId,
            tenantKey = tenantId.key.value,
            level = request.queryParam("level")?.takeIf { it.isNotBlank() },
            logger = request.queryParam("logger")?.takeIf { it.isNotBlank() },
            search = request.queryParam("q")?.takeIf { it.isNotBlank() },
            fromRaw = fromRaw,
            toRaw = toRaw,
            from = parseDateTime(fromRaw, zone),
            to = parseDateTime(toRaw, zone),
        )
    }

    /** The browser's IANA timezone (sent as `tz`), or UTC when absent/invalid. */
    private fun browserZone(request: ServerRequest): ZoneId = request.queryParam("tz")
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: ZoneOffset.UTC

    private fun cursor(request: ServerRequest): Cursor? {
        val ts = request.queryParam("ts")?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: return null
        val id = request.queryParam("id")?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        return Cursor(ts, id)
    }

    /** datetime-local inputs carry no zone; interpret their wall-clock value in the browser's [zone]. */
    private fun parseDateTime(value: String?, zone: ZoneId): OffsetDateTime? = value
        ?.let { runCatching { LocalDateTime.parse(it).atZone(zone).toOffsetDateTime() }.getOrNull() }

    private data class Filters(
        val tenantId: TenantId,
        val tenantKey: String,
        val level: String?,
        val logger: String?,
        val search: String?,
        val fromRaw: String?,
        val toRaw: String?,
        val from: OffsetDateTime?,
        val to: OffsetDateTime?,
    )

    private data class Cursor(val ts: OffsetDateTime, val id: UUID)

    private class Page(
        val logs: List<ApplicationLogEntry>,
        val hasMoreOlder: Boolean,
    ) {
        private val newest = logs.firstOrNull()
        private val oldest = logs.lastOrNull()
        val newerCursorTs: String? = newest?.occurredAt?.toString()
        val newerCursorId: String? = newest?.id?.toString()
        val olderCursorTs: String? = oldest?.occurredAt?.toString()
        val olderCursorId: String? = oldest?.id?.toString()
    }

    private fun ModelBuilder.applyResultsModel(page: Page) {
        "logs" to page.logs
        "hasRows" to page.logs.isNotEmpty()
        "hasOlder" to page.hasMoreOlder
        "newerCursorTs" to page.newerCursorTs
        "newerCursorId" to page.newerCursorId
        "olderCursorTs" to page.olderCursorTs
        "olderCursorId" to page.olderCursorId
    }
}
