package app.epistola.suite.api.v1

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
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
 * Handles API framework exceptions that can occur before Spring selects a
 * controller method. In those cases `@RestControllerAdvice(basePackages = ...)`
 * is not applicable because there is no controller type to match.
 */
@Component
class ApiFrameworkExceptionResolver(
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

        return when (ex) {
            is HttpRequestMethodNotSupportedException -> handleMethodNotSupported(request, response, ex)
            is HttpMediaTypeNotSupportedException -> handleMediaTypeNotSupported(request, response, ex)
            is HttpMediaTypeNotAcceptableException -> handleMediaTypeNotAcceptable(request, response, ex)
            is NoHandlerFoundException -> handleNoHandlerFound(request, response, ex)
            is NoResourceFoundException -> handleNoResourceFound(request, response, ex)
            else -> null
        }
    }

    private fun handleMethodNotSupported(
        request: HttpServletRequest,
        response: HttpServletResponse,
        ex: HttpRequestMethodNotSupportedException,
    ): ModelAndView {
        val supportedMethods = ex.supportedMethods?.toList() ?: emptyList()

        response.status = ApiProblemTypes.METHOD_NOT_ALLOWED.status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        if (supportedMethods.isNotEmpty()) {
            response.setHeader(HttpHeaders.ALLOW, supportedMethods.joinToString(", "))
        }
        objectMapper.writeValue(
            response.writer,
            problemBody(
                request,
                ApiProblemTypes.METHOD_NOT_ALLOWED,
                "HTTP method ${ex.method} is not supported for this resource",
                mapOf("method" to ex.method, "supportedMethods" to supportedMethods),
            ),
        )
        return ModelAndView()
    }

    private fun handleMediaTypeNotSupported(
        request: HttpServletRequest,
        response: HttpServletResponse,
        ex: HttpMediaTypeNotSupportedException,
    ): ModelAndView {
        writeProblem(
            request,
            response,
            ApiProblemTypes.UNSUPPORTED_MEDIA_TYPE,
            "Media type ${ex.contentType} is not supported",
            mapOf("contentType" to ex.contentType.toString(), "supportedTypes" to ex.supportedMediaTypes.map { it.toString() }),
        )
        return ModelAndView()
    }

    private fun handleMediaTypeNotAcceptable(
        request: HttpServletRequest,
        response: HttpServletResponse,
        ex: HttpMediaTypeNotAcceptableException,
    ): ModelAndView {
        writeProblem(
            request,
            response,
            ApiProblemTypes.NOT_ACCEPTABLE,
            "The requested representation is not available",
            mapOf("acceptHeader" to (request.getHeader(HttpHeaders.ACCEPT) ?: ""), "supportedTypes" to ex.supportedMediaTypes.map { it.toString() }),
        )
        return ModelAndView()
    }

    private fun handleNoHandlerFound(
        request: HttpServletRequest,
        response: HttpServletResponse,
        ex: NoHandlerFoundException,
    ): ModelAndView {
        writeProblem(
            request,
            response,
            ApiProblemTypes.NOT_FOUND,
            "No endpoint exists for ${ex.httpMethod} ${ex.requestURL}",
            mapOf("path" to ex.requestURL),
        )
        return ModelAndView()
    }

    private fun handleNoResourceFound(
        request: HttpServletRequest,
        response: HttpServletResponse,
        ex: NoResourceFoundException,
    ): ModelAndView {
        writeProblem(
            request,
            response,
            ApiProblemTypes.NOT_FOUND,
            "Resource not found: ${ex.resourcePath}",
            mapOf("path" to ex.resourcePath),
        )
        return ModelAndView()
    }

    private fun writeProblem(
        request: HttpServletRequest,
        response: HttpServletResponse,
        type: ApiProblemType,
        detail: String,
        extensions: Map<String, Any?> = emptyMap(),
    ) {
        response.status = type.status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        objectMapper.writeValue(response.writer, problemBody(request, type, detail, extensions))
    }
}
