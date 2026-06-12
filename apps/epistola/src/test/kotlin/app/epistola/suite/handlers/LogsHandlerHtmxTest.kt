package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.common.UUIDv7
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.time.OffsetDateTime

/**
 * Server-contract cover for the tenant Logs viewer: the full page renders, the
 * HTMX search returns the results fragment, and the level filter scopes rows.
 */
@SpringBootTest(classes = [EpistolaSuiteApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class LogsHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `logs page renders the tenant's rows and system rows`() {
        val tenantKey = seedLogs()

        val response = get("/tenants/$tenantKey/logs")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Logs")
        assertThat(response.body).contains("first error message")
        assertThat(response.body).contains("a system message")
    }

    @Test
    fun `HTMX search returns the results fragment filtered by level`() {
        val tenantKey = seedLogs()

        val response = getHtmx("/tenants/$tenantKey/logs/search?level=ERROR")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("first error message")
        // The INFO row must be filtered out by the level=ERROR query.
        assertThat(response.body).doesNotContain("an info message")
    }

    private fun seedLogs(): String {
        val tenant: Tenant = createTenant("Logs Viewer")
        val tenantKey = tenant.id.value
        insert(tenantKey, "ERROR", "first error message")
        insert(tenantKey, "INFO", "an info message")
        insert(null, "WARN", "a system message")
        return tenantKey
    }

    private fun insert(tenantKey: String?, level: String, message: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO application_log (id, occurred_at, level, logger, message, instance_id, tenant_key)
                VALUES (:id, :occurredAt, :level, 'logs.viewer.test', :message, 'test-instance', :tenantKey)
                """,
            )
                .bind("id", UUIDv7.generate())
                .bind("occurredAt", OffsetDateTime.now(testClock))
                .bind("level", level)
                .bind("message", message)
                .bind("tenantKey", tenantKey)
                .execute()
        }
    }

    private fun get(url: String): ResponseEntity<String> = restTemplate.getForEntity(url, String::class.java)

    private fun getHtmx(url: String): ResponseEntity<String> {
        val headers = HttpHeaders().apply { set("HX-Request", "true") }
        return restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }
}
