package app.epistola.suite.config

import app.epistola.suite.api.v1.ApiProblemTypes
import app.epistola.suite.api.v1.writeProblemDetail
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException
import tools.jackson.databind.ObjectMapper

/**
 * UI counterpart to `ApiHandlerExceptionResolver`: handles framework errors that happen
 * before Spring selects a handler (unsupported method/media type, unacceptable Accept,
 * unknown path, upload too large) on **non-`/api`** routes.
 *
 * `UiExceptionFilter` only sees exceptions that propagate out of the dispatch; these
 * pre-dispatch failures are consumed by `DispatcherServlet` and would otherwise render the
 * whitelabel `/error` page. This resolver content-negotiates: for structured callers
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

        when (ex) {
            is HttpRequestMethodNotSupportedException -> {
                val supportedMethods = ex.supportedMethods?.toList() ?: emptyList()
                if (supportedMethods.isNotEmpty()) {
                    response.setHeader(HttpHeaders.ALLOW, supportedMethods.joinToString(", "))
                }
                writeProblemDetail(
                    response,
                    objectMapper,
                    request,
                    ApiProblemTypes.METHOD_NOT_ALLOWED,
                    "HTTP method ${ex.method} is not supported for this resource",
                    mapOf("method" to ex.method, "supportedMethods" to supportedMethods),
                )
            }
            is HttpMediaTypeNotSupportedException -> writeProblemDetail(
                response,
                objectMapper,
                request,
                ApiProblemTypes.UNSUPPORTED_MEDIA_TYPE,
                "Media type ${ex.contentType} is not supported",
                mapOf(
                    "contentType" to ex.contentType.toString(),
                    "supportedTypes" to ex.supportedMediaTypes.map { it.toString() },
                ),
            )
            is HttpMediaTypeNotAcceptableException -> writeProblemDetail(
                response,
                objectMapper,
                request,
                ApiProblemTypes.NOT_ACCEPTABLE,
                "The requested representation is not available",
                mapOf(
                    "acceptHeader" to (request.getHeader(HttpHeaders.ACCEPT) ?: ""),
                    "supportedTypes" to ex.supportedMediaTypes.map { it.toString() },
                ),
            )
            is MaxUploadSizeExceededException -> writeProblemDetail(
                response,
                objectMapper,
                request,
                ApiProblemTypes.PAYLOAD_TOO_LARGE,
                "The uploaded content exceeds the maximum allowed size",
                mapOf("maxBytes" to ex.maxUploadSize),
            )
            is NoHandlerFoundException -> writeProblemDetail(
                response,
                objectMapper,
                request,
                ApiProblemTypes.NOT_FOUND,
                "No endpoint exists for ${ex.httpMethod} ${ex.requestURL}",
                mapOf("path" to ex.requestURL),
            )
            is NoResourceFoundException -> writeProblemDetail(
                response,
                objectMapper,
                request,
                ApiProblemTypes.NOT_FOUND,
                "Resource not found: ${ex.resourcePath}",
                mapOf("path" to ex.resourcePath),
            )
            else -> return null
        }
        return ModelAndView()
    }
}
