package app.epistola.suite.audit.ui

import app.epistola.suite.audit.AuditEntry
import app.epistola.suite.audit.queries.AuditPageDirection
import app.epistola.suite.audit.queries.ListAuditEntries
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.queryParam
import app.epistola.suite.htmx.tenantId
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
 * Tenant-scoped Audit viewer (Operations area). Shows the PII-free "who did
 * what, when" trail: the tenant's own rows plus system rows with no tenant,
 * newest first. Filterable by action, outcome, and a date/time range. The
 * default view shows the newest [PAGE_SIZE] rows; "Load newer" / "Load older"
 * page incrementally via keyset cursors (prepending / appending rows) so the
 * table grows in place without offset drift. Reads go through
 * [ListAuditEntries] (permission-gated); no JDBI in the handler.
 *
 * Mirrors [LogsHandler] — the two viewers share the same incremental-paging shape.
 */
@Component
class AuditHandler {

    private companion object {
        const val PAGE_SIZE = 20
        val OUTCOMES = listOf("SUCCESS", "FAILURE")
        val OPERATIONS = listOf("WRITE", "READ")
    }

    /** Full page: filter form + the newest [PAGE_SIZE] rows. */
    fun list(request: ServerRequest): ServerResponse {
        val filters = filters(request)
        val page = query(filters, AuditPageDirection.OLDER, cursor = null)
        return ServerResponse.ok().page("audit/list") {
            "pageTitle" to "Audit - Epistola"
            "tenantId" to filters.tenantKey
            "outcomes" to OUTCOMES
            "selectedOutcome" to filters.outcome
            "operations" to OPERATIONS
            "selectedOperation" to filters.operation
            "actionFilter" to filters.action
            "fromValue" to filters.fromRaw
            "toValue" to filters.toRaw
            applyResultsModel(page)
        }
    }

    /** Filter change: re-render the results region with a fresh newest-first page. */
    fun search(request: ServerRequest): ServerResponse {
        val filters = filters(request)
        val page = query(filters, AuditPageDirection.OLDER, cursor = null)
        return request.htmx {
            fragment("audit/list", "results") {
                "tenantId" to filters.tenantKey
                applyResultsModel(page)
            }
            onNonHtmx { redirect("/tenants/${filters.tenantKey}/audit") }
        }
    }

    /** Load older: append rows below the current oldest, plus a refreshed "older" sentinel. */
    fun older(request: ServerRequest): ServerResponse {
        val filters = filters(request)
        val cursor = cursor(request) ?: return ServerResponse.badRequest().build()
        val page = query(filters, AuditPageDirection.OLDER, cursor)
        return request.htmx {
            fragment("audit/list", "older-rows") {
                "tenantId" to filters.tenantKey
                applyResultsModel(page)
            }
            onNonHtmx { redirect("/tenants/${filters.tenantKey}/audit") }
        }
    }

    /** Load newer: prepend rows above the current newest, keeping the "newer" sentinel. */
    fun newer(request: ServerRequest): ServerResponse {
        val filters = filters(request)
        val cursor = cursor(request) ?: return ServerResponse.badRequest().build()
        val page = query(filters, AuditPageDirection.NEWER, cursor)
        return request.htmx {
            fragment("audit/list", "newer-rows") {
                "tenantId" to filters.tenantKey
                // When nothing newer arrived yet, keep the original cursor so the sentinel still works.
                "newerCursorTs" to (page.newerCursorTs ?: cursor.ts.toString())
                "newerCursorId" to (page.newerCursorId ?: cursor.id.toString())
                "entries" to page.entries
            }
            onNonHtmx { redirect("/tenants/${filters.tenantKey}/audit") }
        }
    }

    // -- internals -----------------------------------------------------------

    private fun query(filters: Filters, direction: AuditPageDirection, cursor: Cursor?): Page {
        val entries = ListAuditEntries(
            tenantId = filters.tenantId.key,
            action = filters.action,
            operation = filters.operation,
            outcome = filters.outcome,
            from = filters.from,
            to = filters.to,
            limit = PAGE_SIZE,
            direction = direction,
            cursorOccurredAt = cursor?.ts,
            cursorId = cursor?.id,
        ).query()
        return Page(entries, hasMoreOlder = direction == AuditPageDirection.OLDER && entries.size == PAGE_SIZE)
    }

    private fun filters(request: ServerRequest): Filters {
        val tenantId = request.tenantId()
        val fromRaw = request.queryParam("from")?.takeIf { it.isNotBlank() }
        val toRaw = request.queryParam("to")?.takeIf { it.isNotBlank() }
        val zone = browserZone(request)
        return Filters(
            tenantId = tenantId,
            tenantKey = tenantId.key.value,
            action = request.queryParam("action")?.takeIf { it.isNotBlank() },
            operation = request.queryParam("operation")?.takeIf { it.isNotBlank() },
            outcome = request.queryParam("outcome")?.takeIf { it.isNotBlank() },
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
        val action: String?,
        val operation: String?,
        val outcome: String?,
        val fromRaw: String?,
        val toRaw: String?,
        val from: OffsetDateTime?,
        val to: OffsetDateTime?,
    )

    private data class Cursor(val ts: OffsetDateTime, val id: UUID)

    private class Page(
        val entries: List<AuditEntry>,
        val hasMoreOlder: Boolean,
    ) {
        private val newest = entries.firstOrNull()
        private val oldest = entries.lastOrNull()
        val newerCursorTs: String? = newest?.occurredAt?.toString()
        val newerCursorId: String? = newest?.id?.toString()
        val olderCursorTs: String? = oldest?.occurredAt?.toString()
        val olderCursorId: String? = oldest?.id?.toString()
    }

    private fun ModelBuilder.applyResultsModel(page: Page) {
        "entries" to page.entries
        "hasRows" to page.entries.isNotEmpty()
        "hasOlder" to page.hasMoreOlder
        "newerCursorTs" to page.newerCursorTs
        "newerCursorId" to page.newerCursorId
        "olderCursorTs" to page.olderCursorTs
        "olderCursorId" to page.olderCursorId
    }
}
