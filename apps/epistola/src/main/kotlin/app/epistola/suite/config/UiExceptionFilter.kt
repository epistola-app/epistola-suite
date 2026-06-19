package app.epistola.suite.config

import app.epistola.suite.api.v1.problemInstance
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

/**
 * Last-resort catch for exceptions that **escape the dispatch** (e.g. thrown from the security
 * filter chain) on non-`/api` routes — so Tomcat never renders a raw stacktrace. Handler-thrown
 * errors are resolved earlier and more richly by [UiHandlerExceptionResolver] (which can render an
 * in-dialog fragment); this filter only ever produces **data or an error page**, never view markup:
 *
 *  - **Structured callers** ([wantsProblemDetail]) → an RFC 9457 `application/problem+json` body
 *    (`type`/`title`/`status`/`detail`/`instance`) whose `type` URI matches the REST API's
 *    `ApiProblemTypes` registry. Never leaks a stacktrace.
 *  - **HTML navigations** → container `sendError`, so the browser gets the error page.
 *
 * Both this filter and the resolver share [resolveUiError]. REST API requests (`/api`) are excluded.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class UiExceptionFilter(
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = request.requestURI.startsWith("/api/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        try {
            filterChain.doFilter(request, response)
        } catch (ex: Exception) {
            val error = resolveUiError(unwrapCause(ex))
            if (wantsProblemDetail(request)) {
                response.status = error.status
                response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
                response.writer.write(objectMapper.writeValueAsString(problemBody(request, error)))
            } else {
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
