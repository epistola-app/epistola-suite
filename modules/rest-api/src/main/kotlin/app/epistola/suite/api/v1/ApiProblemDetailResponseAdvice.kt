package app.epistola.suite.api.v1

import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice

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
        // ProblemDetail stores extension members in `properties`, but RFC 7807
        // extensions are part of the top-level wire object. Keep MVC handlers
        // ProblemDetail-native while preserving the API contract shape.
        response.headers.contentType = MediaType.APPLICATION_PROBLEM_JSON
        body.toProblemMap()
    } else {
        body
    }
}
