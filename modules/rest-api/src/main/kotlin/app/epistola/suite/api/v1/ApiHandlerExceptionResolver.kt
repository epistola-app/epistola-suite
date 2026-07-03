package app.epistola.suite.api.v1

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView
import tools.jackson.databind.ObjectMapper

/**
 * Handles API-path framework errors that happen before Spring selects a controller.
 *
 * [ApiExceptionHandler] extends [org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler]
 * for MVC-handled exceptions, but package-scoped @RestControllerAdvice only
 * applies once a matching controller is known. Pre-dispatch failures such as
 * unsupported method/media type, unacceptable Accept headers, and unknown API
 * paths still need this resolver to preserve application/problem+json responses.
 *
 * The exception→problem mapping itself is [writeFrameworkProblemDetail], shared with
 * the host app's UI resolver so the two surfaces cannot drift apart.
 */
@Component
class ApiHandlerExceptionResolver(
    private val objectMapper: ObjectMapper,
) : HandlerExceptionResolver,
    Ordered {

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun resolveException(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any?,
        ex: Exception,
    ): ModelAndView? {
        if (!request.requestURI.startsWith("/api/")) return null
        if (!writeFrameworkProblemDetail(request, response, objectMapper, ex)) return null
        return ModelAndView()
    }
}
