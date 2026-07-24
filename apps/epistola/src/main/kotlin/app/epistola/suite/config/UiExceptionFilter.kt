// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import app.epistola.suite.api.v1.ApiProblemType
import app.epistola.suite.api.v1.ApiProblemTypes
import app.epistola.suite.api.v1.problemInstance
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.sql.SQLException

/**
 * Catches all exceptions from UI request processing and translates them into
 * appropriate HTTP error responses, preventing Tomcat from rendering raw stacktraces.
 *
 * One structured envelope, content-negotiated by whether the caller wants HTML or data:
 *  - **Structured callers** (a request that `Accept`s `application/json` /
 *    `application/problem+json`, or an HTMX request) → an RFC 9457 `application/problem+json`
 *    body (`type`/`title`/`status`/`detail`/`instance`) whose `type` URI matches the REST
 *    API's [ApiProblemTypes] registry. Never leaks a stacktrace.
 *  - **HTML navigations** (everything else — a browser loading a page) → container
 *    `sendError` so the browser gets an error page rather than raw JSON.
 *
 * Known domain exceptions map to a specific problem type and the status this UI surface
 * has historically used; unknown exceptions become an opaque 500 — details are logged
 * server-side only.
 *
 * REST API requests (under /api) are excluded — they have their own @RestControllerAdvice.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class UiExceptionFilter(
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Resolved error: the HTTP status to use, the problem `type`/`title`, and the detail. */
    private data class UiError(val status: Int, val type: ApiProblemType, val detail: String)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = request.requestURI.startsWith("/api/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        try {
            filterChain.doFilter(request, response)
        } catch (ex: Exception) {
            val error = resolve(ex)
            if (wantsProblemDetail(request)) {
                // RFC 9457 problem+json — same `type` URI as the REST API, no stacktrace.
                response.status = error.status
                response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
                response.writer.write(objectMapper.writeValueAsString(problemBody(request, error)))
            } else {
                // HTML navigation: let the container render an error page.
                response.sendError(error.status, error.detail)
            }
        }
    }

    private fun problemBody(request: HttpServletRequest, error: UiError): Map<String, Any?> = linkedMapOf(
        "type" to error.type.type.toString(),
        "title" to error.type.title,
        "status" to error.status,
        "detail" to error.detail,
        "instance" to request.problemInstance().toString(),
    )

    /**
     * Maps an exception to its status, problem type, and user-facing detail. Add new
     * mappings here as simple class-name checks to avoid coupling this filter to every
     * domain module. The status is the one this UI surface has historically returned
     * (e.g. read-only catalog → 403), independent of the type's canonical REST status.
     */
    private fun resolve(ex: Throwable): UiError {
        // Safety net (#608): an over-length value that slipped past validation and hit a
        // VARCHAR(n) column throws a PostgreSQL string-truncation (SQLSTATE 22001). Map it
        // to a 400 so it never renders as an opaque 500. 22001 carries no column info, so
        // this stays a form-level message rather than a field error. Checked against the
        // full chain (not the unwrapped leaf) so the SQLException is found wherever it sits.
        findStringTruncation(ex)?.let { truncation ->
            log.warn("Over-length input rejected by the database (SQLSTATE 22001): {}", truncation.message)
            return UiError(400, ApiProblemTypes.BAD_REQUEST, "A value you entered is too long.")
        }
        val cause = unwrap(ex)
        return when (cause::class.simpleName) {
            "TenantAccessDeniedException" -> {
                log.warn("Tenant access denied: {}", cause.message)
                UiError(403, ApiProblemTypes.ACCESS_DENIED, "You don't have access to this tenant.")
            }
            "PermissionDeniedException" -> {
                log.warn("Permission denied: {}", cause.message)
                UiError(403, ApiProblemTypes.PERMISSION_DENIED, "You don't have permission to perform this action.")
            }
            "PlatformAccessDeniedException" -> {
                log.warn("Platform access denied: {}", cause.message)
                UiError(403, ApiProblemTypes.PLATFORM_ACCESS_DENIED, "This action requires platform administrator access.")
            }
            "CatalogReadOnlyException" -> {
                log.warn("Catalog read-only: {}", cause.message)
                UiError(403, ApiProblemTypes.CATALOG_READ_ONLY, cause.message ?: "This catalog is read-only.")
            }
            else -> {
                log.error("Unhandled exception on UI request: {} {}", cause::class.simpleName, cause.message, cause)
                UiError(500, ApiProblemTypes.INTERNAL_ERROR, "An unexpected error occurred.")
            }
        }
    }

    /**
     * The PostgreSQL string-truncation (SQLSTATE 22001) in the failure chain — an
     * over-length value that reached a `VARCHAR(n)` column — or null if there is none.
     */
    private fun findStringTruncation(ex: Throwable): SQLException? {
        var c: Throwable? = ex
        while (c != null) {
            if (c is SQLException && c.sqlState == "22001") return c
            c = c.cause
        }
        return null
    }

    /** Unwrap nested exceptions (e.g. from Spring's NestedServletException). */
    private fun unwrap(ex: Throwable): Throwable = ex.cause?.let { unwrap(it) } ?: ex
}

/**
 * Whether a UI request prefers a structured problem body over an HTML page: it accepts JSON /
 * problem+json, or is an HTMX request. Shared by [UiExceptionFilter] and the UI security
 * chain's exception handling so every UI error path content-negotiates the same way.
 */
fun wantsProblemDetail(request: HttpServletRequest): Boolean {
    val accept = request.getHeader("Accept").orEmpty()
    return accept.contains(MediaType.APPLICATION_JSON_VALUE) ||
        accept.contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE) ||
        request.getHeader("HX-Request") == "true"
}
