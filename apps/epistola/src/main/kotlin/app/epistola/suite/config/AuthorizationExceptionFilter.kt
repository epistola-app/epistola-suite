package app.epistola.suite.config

import app.epistola.suite.security.PermissionDeniedException
import app.epistola.suite.security.PlatformAccessDeniedException
import app.epistola.suite.security.TenantAccessDeniedException
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
 * Translates domain-level authorization exceptions into HTTP 403 responses.
 *
 * The functional routing layer (RouterFunction) does not support @ControllerAdvice,
 * so this filter catches authorization exceptions and returns a JSON body with
 * the error message. The client-side HTMX error handler reads this JSON to
 * display a meaningful error banner.
 *
 * REST API requests (under /api) are excluded — they have their own exception handler.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class AuthorizationExceptionFilter : OncePerRequestFilter() {
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
            val message = when (cause) {
                is TenantAccessDeniedException -> {
                    log.warn("Tenant access denied: user={} tenant={}", cause.userEmail, cause.tenantId)
                    "You don't have access to this tenant."
                }
                is PermissionDeniedException -> {
                    log.warn("Permission denied: user={} tenant={} permission={}", cause.userEmail, cause.tenantId, cause.permission)
                    "You don't have permission to perform this action."
                }
                is PlatformAccessDeniedException -> {
                    log.warn("Platform access denied: user={} requiredRole={}", cause.userEmail, cause.requiredRole)
                    "This action requires platform administrator access."
                }
                else -> throw ex
            }

            response.status = HttpServletResponse.SC_FORBIDDEN
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.writer.write("""{"error":"$message"}""")
        }
    }

    /** Unwrap nested exceptions (e.g. from Spring's NestedServletException). */
    private fun unwrap(ex: Throwable): Throwable = ex.cause?.let { unwrap(it) } ?: ex
}
