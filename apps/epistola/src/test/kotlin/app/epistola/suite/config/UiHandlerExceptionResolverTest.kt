// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.HttpRequestMethodNotSupportedException
import tools.jackson.databind.json.JsonMapper

@Tag("unit")
class UiHandlerExceptionResolverTest {
    private val objectMapper = JsonMapper.builder().build()
    private val resolver = UiHandlerExceptionResolver(objectMapper)

    private val ex = HttpRequestMethodNotSupportedException("DELETE", listOf("GET", "POST"))

    @Test
    fun `405 on a UI route with JSON Accept returns problem+json`() {
        val request = MockHttpServletRequest("DELETE", "/tenants/t/themes").apply {
            addHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
        }
        val response = MockHttpServletResponse()

        val mav = resolver.resolveException(request, response, null, ex)

        assertThat(mav).isNotNull()
        assertThat(response.status).isEqualTo(405)
        assertThat(response.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        assertThat(response.getHeader("Allow")).contains("GET", "POST")
        val parsed = objectMapper.readTree(response.contentAsString)
        assertThat(parsed.get("type").asString()).isEqualTo("https://epistola.app/errors/method-not-allowed")
    }

    @Test
    fun `HTML navigations are left to the error page (returns null)`() {
        val request = MockHttpServletRequest("DELETE", "/tenants/t/themes").apply { addHeader("Accept", "text/html") }
        assertThat(resolver.resolveException(request, MockHttpServletResponse(), null, ex)).isNull()
    }

    @Test
    fun `api paths are left to the api resolver (returns null)`() {
        val request = MockHttpServletRequest("DELETE", "/api/tenants/t").apply { addHeader("Accept", MediaType.APPLICATION_JSON_VALUE) }
        assertThat(resolver.resolveException(request, MockHttpServletResponse(), null, ex)).isNull()
    }
}
