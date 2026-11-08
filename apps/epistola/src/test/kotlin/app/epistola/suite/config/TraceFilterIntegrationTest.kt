// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import app.epistola.suite.BaseIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod

/**
 * Every answer names the trace Spring Boot's OpenTelemetry bridge runs the request in, the one its log
 * lines carry: a `traceresponse` header, and on a page `<meta name="traceparent">`. A caller's W3C
 * `traceparent` is continued.
 */
@AutoConfigureTracing
class TraceFilterIntegrationTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `a page names the trace it ran in, continuing the caller's`() {
        val headers = HttpHeaders().apply { set("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01") }

        val response = restTemplate.exchange("/", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)

        val named = response.headers.getFirst("traceresponse")
        assertThat(named).startsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-")
        assertThat(response.body).contains("<meta name=\"traceparent\" content=\"$named\">")
    }
}
