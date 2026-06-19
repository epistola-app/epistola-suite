package app.epistola.suite.config

import app.epistola.suite.api.v1.ApiProblemType
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
import org.thymeleaf.TemplateSpec
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.web.servlet.JakartaServletWebApplication
import tools.jackson.databind.ObjectMapper
import java.util.Locale

/**
 * The UI's MVC-native error handler: it catches exceptions during (and just before) handler
 * dispatch on non-`/api` routes and **renders the response through the view layer** — so no
 * component hand-builds HTML. ([UiExceptionFilter] is only the last-resort net for whatever
 * escapes the dispatch, and it produces data or an error page, never markup.)
 *
 * Two families:
 *  - **Framework pre-dispatch errors** (unsupported method/media type, unknown path, upload too
 *    large) — `problem+json` for structured callers, or `null` (→ the error page).
 *  - **Handler-thrown domain / form / unexpected errors** — resolved by the shared [resolveUiError]
 *    and rendered: for an HTMX form that declared a region via [ERROR_REGION_HEADER] (a create
 *    dialog), an **out-of-band swap of `fragments/dialog :: dialogError`** into that region (200 +
 *    `HX-Reswap: none`, so the form and any chosen file survive); for a data caller (e.g. the
 *    editor), `problem+json` whose `detail` carries the message; for a plain navigation, `null` so
 *    the exception falls through to the error page.
 */
@Component
class UiHandlerExceptionResolver(
    private val objectMapper: ObjectMapper,
    private val templateEngine: SpringTemplateEngine,
) : HandlerExceptionResolver,
    Ordered {

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun resolveException(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any?,
        ex: Exception,
    ): ModelAndView? {
        if (request.requestURI.startsWith("/api/")) return null

        // 1. Framework pre-dispatch errors keep their specific problem types + extensions.
        val framework = frameworkProblem(request, ex)
        if (framework != null) {
            if (!wantsProblemDetail(request)) return null // HTML navigation → error page
            if (ex is HttpRequestMethodNotSupportedException) {
                val methods = ex.supportedMethods?.toList().orEmpty()
                if (methods.isNotEmpty()) response.setHeader(HttpHeaders.ALLOW, methods.joinToString(", "))
            }
            writeProblemDetail(response, objectMapper, request, framework.type, framework.detail, framework.extensions)
            return ModelAndView()
        }

        // 2. Handler-thrown domain / form / unexpected errors.
        val region = request.getHeader(ERROR_REGION_HEADER)
        val isHtmxDialog = request.getHeader("HX-Request") == "true" && !region.isNullOrBlank()
        if (!isHtmxDialog && !wantsProblemDetail(request)) return null // plain navigation → error page

        val error = resolveUiError(unwrapCause(ex))
        if (isHtmxDialog) {
            // Render the message into the dialog's general-error region. The markup lives in the
            // `dialogError` fragment (which escapes both the region id and the message) — this just
            // invokes the engine, the HTML counterpart of writeProblemDetail's objectMapper. 200 +
            // HX-Reswap:none so HTMX applies only the OOB swap and leaves the form/file untouched.
            response.status = HttpServletResponse.SC_OK
            response.setHeader("HX-Reswap", "none")
            response.contentType = "text/html;charset=UTF-8"
            renderDialogError(request, response, region!!, error.detail)
            return ModelAndView()
        }
        writeProblemDetail(response, objectMapper, request, error.type, error.detail, emptyMap())
        return ModelAndView()
    }

    /** Renders the `fragments/dialog :: dialogError` OOB fragment for the given region/message. */
    private fun renderDialogError(
        request: HttpServletRequest,
        response: HttpServletResponse,
        regionId: String,
        message: String,
    ) {
        val application = JakartaServletWebApplication.buildApplication(request.servletContext)
        val context = WebContext(
            application.buildExchange(request, response),
            Locale.getDefault(),
            mapOf("regionId" to regionId, "message" to message),
        )
        val spec = TemplateSpec("fragments/dialog", setOf("dialogError"), null as TemplateMode?, null as Map<String, Any>?)
        response.writer.write(templateEngine.process(spec, context))
    }

    /** A framework pre-dispatch problem: its type, detail, and problem+json extensions. */
    private data class FrameworkProblem(val type: ApiProblemType, val detail: String, val extensions: Map<String, Any?>)

    private fun frameworkProblem(request: HttpServletRequest, ex: Exception): FrameworkProblem? = when (ex) {
        is HttpRequestMethodNotSupportedException -> FrameworkProblem(
            ApiProblemTypes.METHOD_NOT_ALLOWED,
            "HTTP method ${ex.method} is not supported for this resource",
            mapOf("method" to ex.method, "supportedMethods" to (ex.supportedMethods?.toList() ?: emptyList<String>())),
        )
        is HttpMediaTypeNotSupportedException -> FrameworkProblem(
            ApiProblemTypes.UNSUPPORTED_MEDIA_TYPE,
            "Media type ${ex.contentType} is not supported",
            mapOf(
                "contentType" to ex.contentType.toString(),
                "supportedTypes" to ex.supportedMediaTypes.map { it.toString() },
            ),
        )
        is HttpMediaTypeNotAcceptableException -> FrameworkProblem(
            ApiProblemTypes.NOT_ACCEPTABLE,
            "The requested representation is not available",
            mapOf(
                "acceptHeader" to (request.getHeader(HttpHeaders.ACCEPT) ?: ""),
                "supportedTypes" to ex.supportedMediaTypes.map { it.toString() },
            ),
        )
        is MaxUploadSizeExceededException -> FrameworkProblem(
            ApiProblemTypes.PAYLOAD_TOO_LARGE,
            "The uploaded content exceeds the maximum allowed size",
            mapOf("maxBytes" to ex.maxUploadSize),
        )
        is NoHandlerFoundException -> FrameworkProblem(
            ApiProblemTypes.NOT_FOUND,
            "No endpoint exists for ${ex.httpMethod} ${ex.requestURL}",
            mapOf("path" to ex.requestURL),
        )
        is NoResourceFoundException -> FrameworkProblem(
            ApiProblemTypes.NOT_FOUND,
            "Resource not found: ${ex.resourcePath}",
            mapOf("path" to ex.resourcePath),
        )
        else -> null
    }
}
