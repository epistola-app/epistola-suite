// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.logs

import app.epistola.suite.common.UUIDv7
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.logs.queries.GetTenantLogExcerpt
import app.epistola.suite.mediator.query
import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.testing.IntegrationTestBase
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/** The log lines of a tenant's requests, by trace id, as a report attaches them. */
class TenantLogExcerptIT : IntegrationTestBase() {
    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var nodeIdentity: NodeIdentity

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val ingestor by lazy {
        ApplicationLogIngestor(jdbi, nodeIdentity, objectMapper, SimpleMeterRegistry(), ApplicationLogProperties())
    }

    private fun line(tenant: String?, traceId: String, message: String, pattern: String, user: java.util.UUID? = caller) = ApplicationLogRecord(
        id = UUIDv7.generate(),
        occurredAt = OffsetDateTime.now(testClock),
        level = "ERROR",
        logger = "app.epistola.suite.Example",
        message = message,
        thread = "test",
        tenantKey = tenant,
        traceId = traceId,
        spanId = "00f067aa0ba902b7",
        exception = null,
        attributes = null,
        messagePattern = pattern,
        userId = user,
    )

    /** The test principal, whose excerpt is read. */
    private val caller: java.util.UUID by lazy { withMediator { SecurityContext.current().userId.value } }

    @Test
    fun `only the tenant's own lines of the caller's own requests in the named traces, with the message as written in the code`() {
        val tenant = createTenant("Log Excerpt").id
        val other = createTenant("Log Excerpt Other").id
        val trace = "4bf92f3577b34da6a3ce929d0e0e47${(10..99).random()}"
        ingestor.enqueue(line(tenant.value, trace, "Render failed for jan@example.org", "Render failed for {}"))
        ingestor.enqueue(line(other.value, trace, "Someone else's line", "Someone else's line"))
        ingestor.enqueue(line(tenant.value, "0af7651916cd43dd8448eb211c80319c", "Another request", "Another request"))
        // A colleague's line on the same trace (trace ids are no secret): not the caller's request.
        ingestor.enqueue(line(tenant.value, trace, "A colleague's line", "A colleague's line", user = java.util.UUID.randomUUID()))
        // A background line on the same trace, written for nobody.
        ingestor.enqueue(line(tenant.value, trace, "A background line", "A background line", user = null))
        ingestor.flush()

        val excerpt = withMediator { GetTenantLogExcerpt(TenantKey.of(tenant.value), listOf(trace)).query() }

        assertThat(excerpt.lines).hasSize(1)
        assertThat(excerpt.lines.single().message).isEqualTo("Render failed for jan@example.org")
        assertThat(excerpt.lines.single().messagePattern).isEqualTo("Render failed for {}")
        assertThat(excerpt.truncated).isFalse()
    }
}
