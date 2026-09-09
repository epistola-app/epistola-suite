// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.observability

import app.epistola.suite.BaseIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus

class PrometheusEndpointTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `prometheus endpoint returns metrics`() {
        val response = restTemplate.getForEntity("/actuator/prometheus", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.contentType.toString()).startsWith("text/plain")

        val body = response.body!!

        // Verify at least one custom Epistola metric is present (eagerly registered counter)
        assertThat(body).contains("epistola_eventlog_persist_failures")

        // Verify JVM metrics are auto-configured
        assertThat(body).contains("jvm_memory_used_bytes")

        // Common identity tags must be stamped on every series (fleet monitoring).
        assertThat(body).contains("service=\"epistola-suite\"")
        assertThat(body).contains("instance=")
        assertThat(body).contains("installation_id=")
    }

    @Test
    fun `liveness and readiness are exposed on the main port via add-additional-paths`() {
        // In production the actuator health endpoints move to the separate
        // management port; Kubernetes probes rely on these main-port aliases.
        assertThat(restTemplate.getForEntity("/livez", String::class.java).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(restTemplate.getForEntity("/readyz", String::class.java).statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `health endpoint wires the custom storage and job-poller indicators and is UP`() {
        val response = restTemplate.getForEntity("/actuator/health", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body).contains("\"status\":\"UP\"")
        assertThat(body).contains("contentStore")
        assertThat(body).contains("jobPoller")
    }
}
