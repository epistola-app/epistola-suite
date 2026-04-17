package app.epistola.suite.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Catches all exceptions from UI request processing and translates them into
 * appropriate HTTP error responses, preventing Tomcat from rendering raw stacktraces.
 *
 * Known domain exceptions are mapped to specific status codes (403, 404, 409, etc.).
 * Unknown exceptions become 500 with a generic message — details are logged server-side only.
 *
 * REST API requests (under /api) are excluded — they have their own @RestControllerAdvice.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class UiExceptionFilter : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = request.requestURI.startsWith("/api/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        try {
            filterChain.doFilter(request, response)
        } catch (ex: Exception) {
            val cause = unwrap(ex)
            val (status, message) = resolve(cause)

            val acceptsJson = request.getHeader("Accept")?.contains(MediaType.APPLICATION_JSON_VALUE) == true
            val isHtmx = request.getHeader("HX-Request") == "true"

            if (acceptsJson || isHtmx) {
                response.status = status
                response.contentType = MediaType.APPLICATION_JSON_VALUE
                response.writer.write("""{"error":"$message"}""")
            } else {
                response.sendError(status, message)
            }
        }
    }

    /**
     * Maps an exception to an HTTP status code and user-facing message.
     *
     * Add new exception mappings here as simple class name checks to avoid
     * coupling this filter to every domain module.
     */
    private fun resolve(cause: Throwable): Pair<Int, String> = when (cause::class.simpleName) {
        "TenantAccessDeniedException" -> {
            log.warn("Tenant access denied: {}", cause.message)
            403 to "You don't have access to this tenant."
        }
        "PermissionDeniedException" -> {
            log.warn("Permission denied: {}", cause.message)
            403 to "You don't have permission to perform this action."
        }
        "PlatformAccessDeniedException" -> {
            log.warn("Platform access denied: {}", cause.message)
            403 to "This action requires platform administrator access."
        }
        "CatalogReadOnlyException" -> {
            log.warn("Catalog read-only: {}", cause.message)
            403 to (cause.message ?: "This catalog is read-only.")
        }
        else -> {
            log.error("Unhandled exception on {} {}: {}", "UI request", cause::class.simpleName, cause.message, cause)
            500 to "An unexpected error occurred."
        }
    }

    /** Unwrap nested exceptions (e.g. from Spring's NestedServletException). */
    private fun unwrap(ex: Throwable): Throwable = ex.cause?.let { unwrap(it) } ?: ex
}
