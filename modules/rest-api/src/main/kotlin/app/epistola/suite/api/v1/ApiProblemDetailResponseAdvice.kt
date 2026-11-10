// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice
import app.epistola.api.error.ProblemDetails as ContractProblemDetails

@RestControllerAdvice(basePackages = ["app.epistola.suite.api.v1"])
class ApiProblemDetailResponseAdvice : ResponseBodyAdvice<Any> {
    override fun supports(returnType: MethodParameter, converterType: Class<out HttpMessageConverter<*>>): Boolean = true

    override fun beforeBodyWrite(
        body: Any?,
        returnType: MethodParameter,
        selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>,
        request: ServerHttpRequest,
        response: ServerHttpResponse,
    ): Any? = if (body is ProblemDetail) {
        // ProblemDetail stores extension members in `properties`, but RFC 9457
        // extensions are part of the top-level wire object. Keep MVC handlers
        // ProblemDetail-native while preserving the API contract shape.
        response.headers.contentType = MediaType.APPLICATION_PROBLEM_JSON
        withHelp(body, request).toProblemMap()
    } else {
        body
    }

    /**
     * The problem with its `help` link: its type's page with the operation that answered, so the
     * page can give the hints for it (`https://epistola.app/errors/not-found?operation=getTemplate`).
     * The operation is the controller method's name, which is the contract's operationId: the
     * controllers implement the generated interfaces. A problem raised before a controller ran
     * (authentication, the firewall) names no operation and gets no link.
     */
    private fun withHelp(problem: ProblemDetail, request: ServerHttpRequest): ProblemDetail {
        val handler = (request as? ServletServerHttpRequest)?.servletRequest?.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE) as? HandlerMethod
        val slug = ContractProblemDetails.slugFor(problem.type)
        if (handler != null && slug != null && problem.properties?.containsKey(ContractProblemDetails.HELP_PROPERTY) != true) {
            val help = ContractProblemDetails.helpFor(slug, handler.method.name)
            problem.setProperty(ContractProblemDetails.HELP_PROPERTY, withMissingKind(help, problem))
        }
        return problem
    }

    /**
     * The help link with the kind of resource a `not-found` problem says is missing, so the page can
     * give the hints for it (`…/not-found?operation=generateDocument&missing=template`). A term of the
     * contract, never a value: the link is shareable and logged as it is.
     */
    private fun withMissingKind(help: java.net.URI, problem: ProblemDetail): java.net.URI {
        val kind = (problem.properties?.get(ContractProblemDetails.MISSING_PROPERTY) as? Map<*, *>)?.get("kind") as? String ?: return help
        val separator = if (help.query.isNullOrEmpty()) "?" else "&"
        return java.net.URI.create("$help${separator}missing=$kind")
    }
}
