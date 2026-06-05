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
 * Handles API-path framework errors that happen before Spring selects a controller.
 *
 * [ApiExceptionHandler] extends [org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler]
 * for MVC-handled exceptions, but package-scoped @RestControllerAdvice only
 * applies once a matching controller is known. Pre-dispatch failures such as
 * unsupported method/media type, unacceptable Accept headers, and unknown API
 * paths still need this resolver to preserve application/problem+json responses.
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
                    response,
                    objectMapper,
                    request,
                    ApiProblemTypes.METHOD_NOT_ALLOWED,
                    "HTTP method ${ex.method} is not supported for this resource",
                    mapOf("method" to ex.method, "supportedMethods" to supportedMethods),
                )
                return ModelAndView()
            }
            is HttpMediaTypeNotSupportedException -> {
                writeProblemDetail(
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
                return ModelAndView()
            }
            is HttpMediaTypeNotAcceptableException -> {
                writeProblemDetail(
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
                return ModelAndView()
            }
            is NoHandlerFoundException -> {
                writeProblemDetail(
                    response,
                    objectMapper,
                    request,
                    ApiProblemTypes.NOT_FOUND,
                    "No endpoint exists for ${ex.httpMethod} ${ex.requestURL}",
                    mapOf("path" to ex.requestURL),
                )
                return ModelAndView()
            }
            is NoResourceFoundException -> {
                writeProblemDetail(
                    response,
                    objectMapper,
                    request,
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
