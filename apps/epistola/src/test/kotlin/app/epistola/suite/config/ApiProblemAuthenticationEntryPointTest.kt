// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.InsufficientAuthenticationException
import tools.jackson.databind.ObjectMapper

class ApiProblemAuthenticationEntryPointTest {

    private val objectMapper = ObjectMapper()

    @Test
    fun `adds bearer challenge when resource server is enabled`() {
        val response = MockHttpServletResponse()

        ApiProblemAuthenticationEntryPoint(objectMapper, bearerChallengeEnabled = true).commence(
            MockHttpServletRequest("GET", "/api/tenants/acme"),
            response,
            InsufficientAuthenticationException("Authentication required"),
        )

        assertThat(response.status).isEqualTo(401)
        assertThat(response.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer")
        assertThat(response.contentAsString).contains("\"type\":\"https://epistola.app/errors/unauthorized\"")
    }

    @Test
    fun `does not advertise bearer when resource server is disabled`() {
        val response = MockHttpServletResponse()

        ApiProblemAuthenticationEntryPoint(objectMapper, bearerChallengeEnabled = false).commence(
            MockHttpServletRequest("GET", "/api/tenants/acme"),
            response,
            InsufficientAuthenticationException("Authentication required"),
        )

        assertThat(response.status).isEqualTo(401)
        assertThat(response.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull()
        assertThat(response.contentAsString).contains("\"type\":\"https://epistola.app/errors/unauthorized\"")
    }
}
