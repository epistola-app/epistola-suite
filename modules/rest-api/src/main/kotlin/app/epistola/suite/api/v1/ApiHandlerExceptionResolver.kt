package app.epistola.suite.api.v1

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException
import tools.jackson.databind.ObjectMapper

/**
 * Handles API framework exceptions that occur before Spring selects a
 * controller method ([HttpRequestMethodNotSupportedException],
 * [HttpMediaTypeNotSupportedException], [HttpMediaTypeNotAcceptableException],
 * [NoHandlerFoundException], [NoResourceFoundException]).
 *
 * In those cases [org.springframework.web.bind.annotation.RestControllerAdvice]
 * does not fire because there is no controller type to match. This resolver
 * bridges the gap by producing the same RFC 7807 problem-detail format that
 * [ApiExceptionHandler] uses for post-dispatch exceptions, delegating to the
 * shared [problemDetail] builder to avoid duplication.
 *
 * Post-dispatch counterparts live in [ApiExceptionHandler].
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

        when (ex) {
            is HttpRequestMethodNotSupportedException -> {
                val supportedMethods = ex.supportedMethods?.toList() ?: emptyList()
                if (supportedMethods.isNotEmpty()) {
                    response.setHeader(HttpHeaders.ALLOW, supportedMethods.joinToString(", "))
                }
                writeProblemDetail(
                    response, objectMapper, request,
                    ApiProblemTypes.METHOD_NOT_ALLOWED,
                    "HTTP method ${ex.method} is not supported for this resource",
                    mapOf("method" to ex.method, "supportedMethods" to supportedMethods),
                )
                return ModelAndView()
            }
            is HttpMediaTypeNotSupportedException -> {
                writeProblemDetail(
                    response, objectMapper, request,
                    ApiProblemTypes.UNSUPPORTED_MEDIA_TYPE,
                    "Media type ${ex.contentType} is not supported",
                    mapOf(
                        "contentType" to ex.contentType.toString(),
                        "supportedTypes" to ex.supportedMediaTypes.map { it.toString() },
                    ),
                )
                return ModelAndView()
            }
            is HttpMediaTypeNotAcceptableException -> {
                writeProblemDetail(
                    response, objectMapper, request,
                    ApiProblemTypes.NOT_ACCEPTABLE,
                    "The requested representation is not available",
                    mapOf(
                        "acceptHeader" to (request.getHeader("Accept") ?: ""),
                        "supportedTypes" to ex.supportedMediaTypes.map { it.toString() },
                    ),
                )
                return ModelAndView()
            }
            is NoHandlerFoundException -> {
                writeProblemDetail(
                    response, objectMapper, request,
                    ApiProblemTypes.NOT_FOUND,
                    "No endpoint exists for ${ex.httpMethod} ${ex.requestURL}",
                    mapOf("path" to ex.requestURL),
                )
                return ModelAndView()
            }
            is NoResourceFoundException -> {
                writeProblemDetail(
                    response, objectMapper, request,
                    ApiProblemTypes.NOT_FOUND,
                    "Resource not found: ${ex.resourcePath}",
                    mapOf("path" to ex.resourcePath),
                )
                return ModelAndView()
            }
            else -> return null
        }
    }
}
