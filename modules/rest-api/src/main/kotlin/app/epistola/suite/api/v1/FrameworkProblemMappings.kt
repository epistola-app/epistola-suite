package app.epistola.suite.api.v1

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException
import tools.jackson.databind.ObjectMapper

/**
 * Shared mapping of pre-dispatch framework exceptions (unsupported method/media type,
 * unacceptable Accept, unknown path, upload too large) to RFC 9457 problem details.
 *
 * Used by both `ApiHandlerExceptionResolver` (API routes) and the host app's
 * `UiHandlerExceptionResolver` (structured callers on UI routes) so the two surfaces
 * cannot drift apart.
 *
 * @return true when the exception was mapped and the response written; false when the
 *   caller should fall through to its own handling.
 */
fun writeFrameworkProblemDetail(
    request: HttpServletRequest,
    response: HttpServletResponse,
    objectMapper: ObjectMapper,
    ex: Exception,
): Boolean {
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
        else -> return false
    }
    return true
}
