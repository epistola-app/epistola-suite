package app.epistola.suite.config

import app.epistola.suite.api.v1.ApiProblemTypes
import app.epistola.suite.api.v1.writeProblemDetail
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint
import org.springframework.security.web.AuthenticationEntryPoint
import tools.jackson.databind.ObjectMapper

class ApiProblemAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper,
    private val bearerChallengeEnabled: Boolean,
) : AuthenticationEntryPoint {

    private val bearerEntryPoint = BearerTokenAuthenticationEntryPoint()

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        if (bearerChallengeEnabled) {
            bearerEntryPoint.commence(request, response, authException)
        }
        writeProblemDetail(response, objectMapper, request, ApiProblemTypes.UNAUTHORIZED, "Authentication required")
    }
}
