// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import io.micrometer.tracing.Tracer
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Names the trace a request runs in, so a support request can point at the requests it is about
 * (`docs/support-requests.md`, "Server log"). The trace is Spring Boot's: its OpenTelemetry bridge
 * starts one per request (or continues a caller's W3C `traceparent`) in `ServerHttpObservationFilter`,
 * and puts its `traceId` and `spanId` on every log line. This filter runs after that one and only
 * reads the current span: a `traceresponse` header (W3C Trace Context level 2) on every answer, and
 * [PAGE_ATTRIBUTE] for the shell's `<meta name="traceparent">`. Without tracing it does nothing.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class TraceFilter(private val tracer: ObjectProvider<Tracer>) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        tracer.ifAvailable?.currentSpan()?.context()?.let { context ->
            val traceparent = "00-${context.traceId()}-${context.spanId()}-${if (context.sampled() == true) "01" else "00"}"
            request.setAttribute(PAGE_ATTRIBUTE, traceparent)
            if (!response.isCommitted) response.setHeader(TRACERESPONSE, traceparent)
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        const val TRACERESPONSE = "traceresponse"

        /** The request attribute the shell reads the page's trace from (`${traceparent}`). */
        const val PAGE_ATTRIBUTE = "traceparent"
    }
}
