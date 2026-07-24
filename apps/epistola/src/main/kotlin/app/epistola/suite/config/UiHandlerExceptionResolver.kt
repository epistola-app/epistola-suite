// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import app.epistola.suite.api.v1.writeFrameworkProblemDetail
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView
import tools.jackson.databind.ObjectMapper

/**
 * UI counterpart to `ApiHandlerExceptionResolver`: handles framework errors that happen
 * before Spring selects a handler (unsupported method/media type, unacceptable Accept,
 * unknown path, upload too large) on **non-`/api`** routes.
 *
 * `UiExceptionFilter` only sees exceptions that propagate out of the dispatch; these
 * pre-dispatch failures are consumed by `DispatcherServlet` and would otherwise render the
 * whitelabel `/error` page. The exception→problem mapping is `writeFrameworkProblemDetail`,
 * shared with the API resolver. This resolver content-negotiates: for structured callers
 * ([wantsProblemDetail]) it writes `application/problem+json`; for HTML navigations it
 * returns `null` so the normal error page (`error/error.html`) handles them.
 */
@Component
class UiHandlerExceptionResolver(
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
        // `/api/**` has its own resolver; HTML navigations fall through to the error page.
        if (request.requestURI.startsWith("/api/")) return null
        if (!wantsProblemDetail(request)) return null

        if (!writeFrameworkProblemDetail(request, response, objectMapper, ex)) return null
        return ModelAndView()
    }
}
