package app.epistola.suite.observability

import app.epistola.suite.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles

@Import(TestcontainersConfiguration::class, UnloggedTablesTestConfiguration::class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "epistola.demo.enabled=false",
        "management.prometheus.metrics.export.enabled=true",
    ],
)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@Tag("integration")
class PrometheusEndpointTest {

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
    }
}
