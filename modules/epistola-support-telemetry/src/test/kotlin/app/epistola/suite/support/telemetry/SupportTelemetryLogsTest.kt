// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.telemetry

import app.epistola.suite.support.SupportProperties
import app.epistola.suite.support.connection.SupportScope
import app.epistola.suite.support.telemetry.OtlpSink.Companion.asStrings
import app.epistola.suite.testing.FakeSupportResponse
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID

/**
 * The logs leg against a stand-in support service: what is sent, how, and that the `telemetry`
 * scope decides it at runtime — on the logging thread and again at export.
 */
class SupportTelemetryLogsTest {
    private val sink = OtlpSink()
    private val connection = FakeSupportConnection(otlp = sink.otlpEndpoint)
    private val loggerName = "com.example.telemetrytest.L${UUID.randomUUID().toString().take(8)}"
    private val logger = (LoggerFactory.getLogger(loggerName) as Logger).apply {
        level = Level.TRACE
        isAdditive = false
    }

    private fun leg(properties: TelemetryProperties = TelemetryProperties(logScheduleDelay = Duration.ofMinutes(5), exportTimeout = Duration.ofSeconds(10))) = SupportTelemetryLogs(properties, SupportProperties(enabled = true), connection, testResource()).also { it.attach(loggerName) }

    private var started: SupportTelemetryLogs? = null

    private fun started(properties: TelemetryProperties? = null): SupportTelemetryLogs = (if (properties == null) leg() else leg(properties)).also { started = it }

    @AfterEach
    fun tearDown() {
        started?.stop()
        sink.close()
    }

    private fun bodies(): List<String> = sink.logRequests.flatMap { request ->
        OtlpSink.logs(request).resourceLogsList.flatMap { it.scopeLogsList }.flatMap { it.logRecordsList }.map { it.body.stringValue }
    }

    @Test
    fun `sends gzip protobuf with the bearer token and the suite's resource once telemetry is granted`() {
        connection.grant()
        val leg = started()

        logger.warn("preview failed for template letter")
        leg.flush()

        val request = sink.logRequests.single()
        assertThat(request.header("Content-Type")).isEqualTo("application/x-protobuf")
        assertThat(request.header("Content-Encoding")).isEqualTo("gzip")
        assertThat(request.header("Authorization")).isEqualTo("Bearer tok-1")
        val resourceLogs = OtlpSink.logs(request).resourceLogsList.single()
        assertThat(resourceLogs.resource.attributesList.asStrings()).containsAllEntriesOf(
            mapOf(
                "service.name" to "epistola-suite",
                "service.instance.id" to "node-1",
                "service.version" to "2.0.0-test",
                "deployment.environment" to "test",
                "epistola.installation.id" to "01928f6e-0000-7000-8000-000000000001",
            ),
        )
        val record = resourceLogs.scopeLogsList.single().logRecordsList.single()
        assertThat(record.body.stringValue).isEqualTo("preview failed for template letter")
        assertThat(record.severityText).isEqualTo("WARN")
        assertThat(record.attributesList.asStrings()).containsEntry("logger.name", loggerName)
    }

    @Test
    fun `sends nothing while pending, and starts once telemetry is granted without a restart`() {
        val leg = started()

        logger.warn("while pending")
        leg.flush()
        assertThat(sink.logRequests).isEmpty()
        assertThat(leg.isForwarding()).isFalse()

        connection.grant()
        leg.refreshGate()
        logger.warn("after the grant")
        leg.flush()

        assertThat(leg.isForwarding()).isTrue()
        assertThat(bodies()).containsExactly("after the grant")
    }

    @Test
    fun `stops when telemetry is withdrawn`() {
        connection.grant()
        val leg = started()
        logger.warn("granted")
        leg.flush()

        connection.withdraw()
        leg.refreshGate()
        logger.warn("withdrawn")
        leg.flush()

        assertThat(bodies()).containsExactly("granted")
    }

    @Test
    fun `a 403 insufficient_scope tells the connection, and the next batches are dropped`() {
        connection.grant()
        sink.server.route("POST", "/otlp/v1/logs") { FakeSupportResponse.insufficientScope("telemetry") }
        val leg = started()

        logger.warn("refused")
        leg.flush()
        logger.warn("after the refusal")
        leg.flush()

        assertThat(connection.refused).containsExactly(SupportScope.TELEMETRY)
        assertThat(sink.logRequests).`as`("one refused batch, then none until it is granted again").hasSize(1)
    }

    @Test
    fun `a 401 tells the connection which token the support service no longer accepts`() {
        connection.grant()
        sink.server.route("POST", "/otlp/v1/logs") { FakeSupportResponse.invalidToken() }
        val leg = started()

        logger.warn("after the signing key changed")
        leg.flush()

        assertThat(connection.rejected).containsExactly("tok-1")
        assertThat(connection.refused).`as`("not a withdrawal").isEmpty()
    }

    @Test
    fun `a batch the service takes tells the connection the token is accepted`() {
        connection.grant()
        val leg = started()

        logger.warn("accepted")
        leg.flush()

        assertThat(connection.accepted).containsExactly("tok-1")
        assertThat(connection.rejected).isEmpty()
    }

    @Test
    fun `drops at export what was queued before telemetry was withdrawn`() {
        connection.grant()
        val leg = started()
        logger.warn("queued while granted")

        connection.withdraw() // the logging-thread flag has not caught up yet
        leg.flush()

        assertThat(sink.logRequests).isEmpty()
    }

    @Test
    fun `uses the token the connection holds at each export`() {
        connection.grant()
        val leg = started()
        logger.warn("first")
        leg.flush()

        connection.token = "tok-2"
        logger.warn("second")
        leg.flush()

        assertThat(sink.logRequests.map { it.header("Authorization") }).containsExactly("Bearer tok-1", "Bearer tok-2")
    }

    @Test
    fun `waits for the endpoint and a token instead of sending without them`() {
        connection.grant()
        connection.otlp = null
        connection.token = null
        val leg = started()
        logger.warn("no endpoint yet")
        leg.flush()

        connection.otlp = sink.otlpEndpoint
        logger.warn("no token yet")
        leg.flush()
        assertThat(sink.server.requests).isEmpty()

        connection.token = "tok-1"
        logger.warn("both known")
        leg.flush()

        assertThat(bodies()).containsExactly("both known")
    }

    @Test
    fun `forwards only events at or above the configured level`() {
        connection.grant()
        val leg = started(TelemetryProperties(logLevel = "WARN", logScheduleDelay = Duration.ofMinutes(5), exportTimeout = Duration.ofSeconds(10)))

        logger.info("routine")
        logger.error("broken")
        leg.flush()

        assertThat(bodies()).containsExactly("broken")
    }

    @Test
    fun `a stopped leg no longer forwards`() {
        connection.grant()
        val leg = started()
        leg.stop()

        logger.warn("after stop")
        leg.flush()

        assertThat(sink.logRequests).isEmpty()
    }

    @Test
    fun `stopping does not wait on a hanging support service`() {
        sink.server.fault(app.epistola.suite.testing.FakeSupportFault.Hang(maxMillis = 20_000), "POST", "/otlp/v1/logs")
        connection.grant()
        val leg = started()
        repeat(600) { logger.warn("queued {}", it) }

        val took = kotlin.system.measureTimeMillis { leg.stop() }

        assertThat(took).isLessThan(2_000)
    }

    @Test
    fun `stopping does not retry against a support service that refuses connections`() {
        val closedPort = java.net.ServerSocket(0).use { it.localPort }
        val down = FakeSupportConnection(otlp = java.net.URI("http://127.0.0.1:$closedPort/otlp")).also { it.grant() }
        val leg = SupportTelemetryLogs(TelemetryProperties(logScheduleDelay = Duration.ofMinutes(5), exportTimeout = Duration.ofSeconds(10)), SupportProperties(enabled = true), down, testResource())
            .also { it.attach(loggerName) }
        started = leg
        repeat(1_500) { logger.warn("queued {}", it) }

        val took = kotlin.system.measureTimeMillis { leg.stop() }

        assertThat(took).isLessThan(2_000)
    }
}
