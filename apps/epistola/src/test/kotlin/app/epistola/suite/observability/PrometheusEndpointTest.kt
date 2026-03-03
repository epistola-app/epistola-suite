package app.epistola.suite.observability

import app.epistola.suite.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.client.RestClient

@Import(TestcontainersConfiguration::class, UnloggedTablesTestConfiguration::class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "epistola.demo.enabled=false",
        "management.server.port=0",
        "management.prometheus.metrics.export.enabled=true",
    ],
)
@ActiveProfiles("test")
@Tag("integration")
class PrometheusEndpointTest {

    @LocalManagementPort
    private var managementPort: Int = 0

    @Autowired
    private lateinit var restClientBuilder: RestClient.Builder

    @Test
    fun `prometheus endpoint returns metrics`() {
        val client = restClientBuilder.baseUrl("http://localhost:$managementPort").build()
        val response = client.get()
            .uri("/actuator/prometheus")
            .retrieve()
            .toEntity(String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.contentType.toString()).startsWith("text/plain")

        val body = response.body!!

        // Verify at least one custom Epistola metric is present (eagerly registered counter)
        assertThat(body).contains("epistola_eventlog_persist_failures")

        // Verify JVM metrics are auto-configured
        assertThat(body).contains("jvm_memory_used_bytes")
    }
}
