// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.UUIDv7
import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.common.ids.SupportRequestKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.logs.ApplicationLogIngestor
import app.epistola.suite.logs.ApplicationLogRecord
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.requests.SupportRequestPriority
import app.epistola.suite.support.requests.commands.SyncSupportRequestPriority
import app.epistola.suite.support.requests.desk.SetSupportDeskPriority
import app.epistola.suite.support.requests.queries.GetSupportRequest
import app.epistola.suite.support.requests.queries.ListSupportRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import java.time.OffsetDateTime

/** Submitting feedback that does not validate shows the form again in its dialog, with the errors by their fields. */
class SupportRequestSubmitHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var jdbi: org.jdbi.v3.core.Jdbi

    @Autowired
    private lateinit var nodeIdentity: app.epistola.suite.observability.NodeIdentity

    @Autowired
    private lateinit var objectMapper: tools.jackson.databind.ObjectMapper

    /** The application log's writer, on its own: capture is off in tests, so the test writes the line it needs. */
    private val ingestor by lazy {
        ApplicationLogIngestor(jdbi, nodeIdentity, objectMapper, io.micrometer.core.instrument.simple.SimpleMeterRegistry(), app.epistola.suite.logs.ApplicationLogProperties())
    }

    private fun submit(tenant: TenantKey, title: String, extra: Map<String, String>) = restTemplate.postForEntity(
        "/tenants/${tenant.value}/support/requests",
        HttpEntity(
            LinkedMultiValueMap<String, String>().apply {
                add("title", title)
                add("description", "The preview spins")
                add("category", "BUG")
                add("priority", "MEDIUM")
                extra.forEach { (k, v) -> add(k, v) }
            },
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_FORM_URLENCODED
                set("HX-Request", "true")
            },
        ),
        String::class.java,
    )

    private fun submitted(tenant: TenantKey, title: String) = withMediator {
        val summary = ListSupportRequest(tenant).query().single { it.title == title }
        GetSupportRequest(SupportRequestId(summary.id, TenantId(tenant))).query()!!
    }

    @Test
    fun `a screenshot is served as the image it was taken as, never cached by a shared proxy`() {
        val tenant = createTenant("Support Request Screenshot").id
        assertThat(submit(tenant, "With screenshot", mapOf("screenshotData" to "data:image/png;base64,iVBORw0KGgo=")).statusCode.is2xxSuccessful).isTrue()
        val request = submitted(tenant, "With screenshot")
        val asset = withMediator { app.epistola.suite.support.requests.queries.ListSupportRequestAssets(SupportRequestId(request.id, TenantId(tenant))).query() }.single()

        val response = restTemplate.getForEntity("/tenants/${tenant.value}/support/requests/${request.id.value}/assets/${asset.id.value}/content", ByteArray::class.java)

        assertThat(response.headers.contentType.toString()).isEqualTo("image/png")
        assertThat(response.headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff")
        assertThat(response.headers.cacheControl).contains("private").doesNotContain("public")
    }

    @Test
    fun `a page names its trace for the support request form, in a meta tag and a traceresponse header`() {
        val tenant = createTenant("Feedback Trace Page")

        val page = restTemplate.getForEntity("/tenants/${tenant.id.value}/support/requests", String::class.java)

        val traceresponse = page.headers.getFirst("traceresponse")
        // Sampled or not, as the tracing configuration decides; the ids are in the log lines either way.
        assertThat(traceresponse).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]")
        assertThat(page.body).contains("<meta name=\"traceparent\" content=\"$traceresponse\">")
    }

    @Test
    fun `the server log of the submitter's last requests is attached when they choose, pseudonymised by default`() {
        val tenant = createTenant("Feedback Server Log").id
        val trace = "4bf92f3577b34da6a3ce929d0e0e" + (1000..9999).random()
        // A line the tenant's request logged, as the logging appender records it.
        ingestor.enqueue(
            ApplicationLogRecord(
                id = UUIDv7.generate(), occurredAt = OffsetDateTime.now(testClock), level = "ERROR", logger = "app.epistola.suite.Example",
                message = "Render failed for jan@example.org", thread = "t", tenantKey = tenant.value, traceId = trace, spanId = "00f067aa0ba902b7",
                exception = null, attributes = null, messagePattern = "Render failed for {}",
                // Written for the submitter's own request: the excerpt holds only the caller's lines.
                userId = withMediator { app.epistola.suite.security.SecurityContext.current().userId.value },
            ),
        )
        ingestor.flush()

        assertThat(submit(tenant, "With log", mapOf("attachServerLogs" to "true", "traceIds" to "[\"$trace\"]")).statusCode.is2xxSuccessful).isTrue()
        assertThat(submit(tenant, "Without log", mapOf("traceIds" to "[\"$trace\"]")).statusCode.is2xxSuccessful).isTrue()

        val withLog = submitted(tenant, "With log").serverLogs
        assertThat(withLog).contains("Render failed for {}").contains(trace).doesNotContain("jan@example.org")
        assertThat(submitted(tenant, "Without log").serverLogs).`as`("not chosen, not attached").isNull()
    }

    @Test
    fun `pseudonymised, a library's log line goes into the attached server log without its message`() {
        val tenant = createTenant("Support Request Library Log").id
        val trace = "4bf92f3577b34da6a3ce929d0e0e" + (1000..9999).random()
        ingestor.enqueue(
            ApplicationLogRecord(
                id = UUIDv7.generate(), occurredAt = OffsetDateTime.now(testClock), level = "WARN", logger = "org.springframework.web.Resolver",
                message = "Failed to convert value: Invalid UUID string: jan@example.org", thread = "t", tenantKey = tenant.value, traceId = trace, spanId = "00f067aa0ba902b7",
                exception = null, attributes = null, messagePattern = null,
                userId = withMediator { app.epistola.suite.security.SecurityContext.current().userId.value },
            ),
        )
        ingestor.flush()

        assertThat(submit(tenant, "Library log", mapOf("attachServerLogs" to "true", "traceIds" to "[\"$trace\"]")).statusCode.is2xxSuccessful).isTrue()

        val serverLogs = submitted(tenant, "Library log").serverLogs
        assertThat(serverLogs).contains("org.springframework.web.Resolver").doesNotContain("jan@example.org")
    }

    @Test
    fun `a title of only spaces returns the form with its error, shaped for the dialog`() {
        val tenant = createTenant("Feedback Submit")
        val form = LinkedMultiValueMap<String, String>().apply {
            add("title", "   ") // passes the browser's `required`, trims to blank on the server
            add("description", "Something is off")
            add("category", "BUG")
            add("priority", "MEDIUM")
        }

        val response = restTemplate.postForEntity(
            "/tenants/${tenant.id.value}/support/requests",
            HttpEntity(
                form,
                HttpHeaders().apply {
                    contentType = MediaType.APPLICATION_FORM_URLENCODED
                    set("HX-Request", "true")
                },
            ),
            String::class.java,
        )

        assertThat(response.statusCode.value()).isEqualTo(422)
        assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("innerHTML")
        assertThat(response.body).`as`("the form, not a whole page").doesNotContain("<html").contains("data-support-request-form")
        assertThat(response.body).contains("class=\"form-error\"").contains("Something is off")
    }

    @Test
    fun `a blank comment is refused in its form`() {
        val tenant = createTenant("Feedback Comment")

        val response = restTemplate.postForEntity(
            "/tenants/${tenant.id.value}/support/requests/${SupportRequestKey.generate().value}/comments",
            HttpEntity(
                LinkedMultiValueMap<String, String>().apply { add("body", "   ") },
                HttpHeaders().apply {
                    contentType = MediaType.APPLICATION_FORM_URLENCODED
                    set("HX-Request", "true")
                },
            ),
            String::class.java,
        )

        assertThat(response.statusCode.value()).isEqualTo(422)
        assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("none")
        assertThat(response.body).contains("id=\"add-comment-error\"").contains("Write a comment first.")
    }

    @Test
    fun `the detail page shows the requested priority beside the one the handler set, once they differ`() {
        val tenant = createTenant("Priority Detail").id
        assertThat(submit(tenant, "Prioritised", emptyMap()).statusCode.is2xxSuccessful).isTrue()
        val request = submitted(tenant, "Prioritised")
        val detail = { restTemplate.getForEntity("/tenants/${tenant.value}/support/requests/${request.id.value}", String::class.java).body!! }
        assertThat(detail()).doesNotContain("support-request-requested-priority")

        withMediator { SetSupportDeskPriority(tenant, request.id, SupportRequestPriority.CRITICAL).execute() }

        assertThat(detail()).contains("data-testid=\"support-request-requested-priority\"").contains("Requested: ")
    }
}
