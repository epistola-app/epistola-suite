// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.telemetry

import app.epistola.suite.support.connection.SupportScope
import app.epistola.suite.support.telemetry.OtlpSink.Companion.asStrings
import app.epistola.suite.testing.FakeSupportResponse
import io.micrometer.core.instrument.MockClock
import io.micrometer.registry.otlp.OtlpMeterRegistry
import io.micrometer.registry.otlp.OtlpMetricsSender
import io.opentelemetry.proto.metrics.v1.AggregationTemporality
import io.opentelemetry.proto.metrics.v1.Metric
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The metrics leg against a stand-in support service: payload, transport and the `telemetry`
 * scope deciding each publish.
 */
class SupportTelemetryMetricsTest {
    private val sink = OtlpSink()
    private val connection = FakeSupportConnection(otlp = sink.otlpEndpoint)
    private val clock = MockClock()
    private val step = Duration.ofMinutes(1)
    private var registry = registry(stripTenantTag = true)

    private fun registry(stripTenantTag: Boolean) = SupportTelemetryMeterRegistry.create(
        TelemetryProperties(metricStep = step, exportTimeout = Duration.ofSeconds(10), stripTenantTagFromMetrics = stripTenantTag),
        SupportOtlpTarget(connection),
        testResource(),
        Duration.ofSeconds(5),
        clock,
    )

    @AfterEach
    fun tearDown() {
        connection.withdraw() // no final publish on close
        registry.close()
        sink.close()
    }

    /** Counts [n] renders in the current step, then moves to the next step and publishes. */
    private fun renderAndPublish(n: Int = 3) {
        registry.counter("epistola.test.renders", "tenant", "acme", "outcome", "ok").increment(n.toDouble())
        clock.add(step)
        publish(registry.otlp)
    }

    private fun renders(index: Int = 0): Metric = OtlpSink.metrics(sink.metricRequests[index]).resourceMetricsList.single()
        .scopeMetricsList.flatMap { it.metricsList }
        .single { it.name == "epistola.test.renders" }

    @Test
    fun `publishes gzip protobuf deltas with the bearer token, the suite's resource and no tenant tag`() {
        connection.grant()

        renderAndPublish(3)

        val request = sink.metricRequests.single()
        assertThat(request.header("Content-Type")).isEqualTo("application/x-protobuf")
        assertThat(request.header("Content-Encoding")).isEqualTo("gzip")
        assertThat(request.header("Authorization")).isEqualTo("Bearer tok-1")
        val resource = OtlpSink.metrics(request).resourceMetricsList.single().resource.attributesList.asStrings()
        assertThat(resource).containsAllEntriesOf(
            mapOf(
                "service.name" to "epistola-suite",
                "service.instance.id" to "node-1",
                "service.version" to "2.0.0-test",
                "deployment.environment" to "test",
                "epistola.installation.id" to "01928f6e-0000-7000-8000-000000000001",
            ),
        )
        val sum = renders().sum
        assertThat(sum.aggregationTemporality).isEqualTo(AggregationTemporality.AGGREGATION_TEMPORALITY_DELTA)
        val point = sum.dataPointsList.single()
        assertThat(point.asDouble).isEqualTo(3.0)
        assertThat(point.attributesList.asStrings()).containsEntry("outcome", "ok").doesNotContainKey("tenant")
    }

    @Test
    fun `the template tag never reaches the support service, and its series add up instead of colliding`() {
        connection.grant()

        registry.counter("epistola.test.renders", "template", "letter", "outcome", "ok").increment(5.0)
        registry.counter("epistola.test.renders", "template", "invoice", "outcome", "ok").increment(7.0)
        clock.add(step)
        publish(registry.otlp)

        val point = renders().sum.dataPointsList.single()
        assertThat(point.asDouble).isEqualTo(12.0)
        assertThat(point.attributesList.asStrings()).doesNotContainKey("template")
    }

    @Test
    fun `keeps the tenant tag when stripping is off`() {
        registry.close()
        registry = registry(stripTenantTag = false)
        connection.grant()

        renderAndPublish()

        assertThat(renders().sum.dataPointsList.single().attributesList.asStrings()).containsEntry("tenant", "acme")
    }

    @Test
    fun `publishes nothing while pending, and starts once telemetry is granted`() {
        renderAndPublish()
        assertThat(sink.server.requests).isEmpty()

        connection.grant()
        renderAndPublish(5)

        assertThat(sink.metricRequests).hasSize(1)
        assertThat(renders().sum.dataPointsList.single().asDouble).isEqualTo(5.0) // the pending step's 3 are gone
    }

    @Test
    fun `stops when telemetry is withdrawn`() {
        connection.grant()
        renderAndPublish()

        connection.withdraw()
        renderAndPublish()

        assertThat(sink.metricRequests).hasSize(1)
    }

    @Test
    fun `a 403 insufficient_scope tells the connection, and the next publish is dropped`() {
        connection.grant()
        sink.server.route("POST", "/otlp/v1/metrics") { FakeSupportResponse.insufficientScope("telemetry") }

        renderAndPublish()
        renderAndPublish()

        assertThat(connection.refused).containsExactly(SupportScope.TELEMETRY)
        assertThat(sink.metricRequests).`as`("one refused publish, then none until it is granted again").hasSize(1)
    }

    @Test
    fun `a 401 tells the connection which token the support service no longer accepts`() {
        connection.grant()
        sink.server.route("POST", "/otlp/v1/metrics") { FakeSupportResponse.invalidToken() }

        renderAndPublish()

        assertThat(connection.rejected).containsExactly("tok-1")
        assertThat(connection.refused).`as`("not a withdrawal").isEmpty()
    }

    @Test
    fun `a publish the service takes tells the connection the token is accepted`() {
        connection.grant()

        renderAndPublish()

        assertThat(connection.accepted).containsExactly("tok-1")
    }

    @Test
    fun `any other failed publish is not taken for a withdrawal`() {
        connection.grant()
        sink.server.route("POST", "/otlp/v1/metrics") { FakeSupportResponse.json(403, """{"error":"forbidden"}""") }

        renderAndPublish()

        assertThat(connection.refused).isEmpty()
    }

    @Test
    fun `uses the token the connection holds at each publish`() {
        connection.grant()
        renderAndPublish()
        connection.token = "tok-2"
        renderAndPublish()

        assertThat(sink.metricRequests.map { it.header("Authorization") }).containsExactly("Bearer tok-1", "Bearer tok-2")
    }

    @Test
    fun `never sends to the placeholder while the endpoint is unknown, nor without a token`() {
        val sent = mutableListOf<OtlpMetricsSender.Request>()
        val target = SupportOtlpTarget(connection)
        val sender = ScopeGatedOtlpMetricsSender(target) { sent += it }
        val config = SupportOtlpConfig(step, testResource())
        connection.grant()
        connection.otlp = null

        val request = OtlpMetricsSender.Request.builder(byteArrayOf(1)).address(config.url()).headers(config.headers()).build()
        sender.send(request)
        assertThat(config.url()).isEqualTo(SupportOtlpConfig.PLACEHOLDER_URL)
        assertThat(sent).isEmpty()

        connection.otlp = sink.otlpEndpoint
        connection.token = null
        sender.send(request)
        assertThat(sent).isEmpty()

        connection.token = "tok-3"
        sender.send(request)
        assertThat(sent.single().address).isEqualTo("${sink.otlpEndpoint}/v1/metrics")
        assertThat(sent.single().headers).containsEntry("Authorization", "Bearer tok-3")
    }

    @Test
    fun `the OTLP settings come from the suite, not from OTEL environment defaults`() {
        connection.grant()
        val config = SupportOtlpConfig(step, testResource())

        // The sender addresses each connection itself; the registry's own address is never sent to.
        assertThat(config.url()).isEqualTo(SupportOtlpConfig.PLACEHOLDER_URL)
        assertThat(config.headers()).isEmpty()
        assertThat(config.resourceAttributes()).containsEntry("service.name", "epistola-suite")
        assertThat(config.aggregationTemporality()).isEqualTo(io.micrometer.registry.otlp.AggregationTemporality.DELTA)
        assertThat(config.compressionMode()).isEqualTo(io.micrometer.registry.otlp.CompressionMode.GZIP)
        assertThat(config.step()).isEqualTo(step)
    }

    @Test
    fun `a publish goes to every connection that grants telemetry, rewritten for each, and one failing holds back no other`() {
        val sent = mutableListOf<OtlpMetricsSender.Request>()
        val epistola = FakeSupportConnection(otlp = java.net.URI("https://epistola.example/otlp"), token = "e")
        val partner = FakeSupportConnection(otlp = java.net.URI("https://partner.example/otlp"), token = "p", id = java.util.UUID.fromString("01900000-0000-7000-8000-0000000000aa"))
        val pending = FakeSupportConnection(otlp = java.net.URI("https://pending.example/otlp"), token = "x", id = java.util.UUID.fromString("01900000-0000-7000-8000-0000000000bb"))
        epistola.grant()
        partner.grant()
        val targets = listOf(
            SupportOtlpTarget(epistola, metricsRewrite = { byteArrayOf(1) }),
            SupportOtlpTarget(partner, metricsRewrite = { byteArrayOf(2) }),
            SupportOtlpTarget(pending),
        )
        val sender = ScopeGatedOtlpMetricsSender({ targets }) { request ->
            if (request.address.orEmpty().startsWith("https://epistola.example")) throw java.io.IOException("unreachable")
            sent += request
        }

        sender.send(OtlpMetricsSender.Request.builder(byteArrayOf(0)).address(SupportOtlpConfig.PLACEHOLDER_URL).build())

        assertThat(sent.map { it.address }).containsExactly("https://partner.example/otlp/v1/metrics")
        assertThat(sent.single().metricsData).containsExactly(2)
        assertThat(sent.single().headers).containsEntry("Authorization", "Bearer p")
    }

    private companion object {
        /** `publish()` is protected; tests publish a step on demand instead of waiting for the registry's thread. */
        fun publish(registry: OtlpMeterRegistry) {
            OtlpMeterRegistry::class.java.getDeclaredMethod("publish").apply { isAccessible = true }.invoke(registry)
        }
    }

    @Test
    fun `closing does not wait for a scheduled publish that hangs on the support service`() {
        sink.server.fault(app.epistola.suite.testing.FakeSupportFault.Hang(maxMillis = 20_000), "POST", "/otlp/v1/metrics")
        connection.grant()
        registry.counter("epistola.test.renders", "outcome", "ok").increment()
        clock.add(step)
        // A scheduled publish, as Micrometer's own scheduler runs it: it holds the in-progress semaphore while it hangs.
        val scheduled = io.micrometer.core.instrument.push.PushMeterRegistry::class.java
            .getDeclaredMethod("publishSafelyOrSkipIfInProgress").apply { isAccessible = true }
        Thread.ofPlatform().daemon().start { scheduled.invoke(registry.otlp) }
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (sink.metricRequests.isEmpty() && System.nanoTime() < deadline) Thread.onSpinWait()
        assertThat(sink.metricRequests).`as`("the publish reached the hanging service").isNotEmpty()

        val took = kotlin.system.measureTimeMillis { registry.close() }

        assertThat(took).isLessThan(3_000)
    }

    @Test
    fun `closing does not wait on a hanging support service`() {
        sink.server.fault(app.epistola.suite.testing.FakeSupportFault.Hang(maxMillis = 20_000), "POST", "/otlp/v1/metrics")
        connection.grant()
        registry.counter("epistola.test.renders", "outcome", "ok").increment()
        clock.add(step)

        val took = kotlin.system.measureTimeMillis { registry.close() }

        assertThat(took).isLessThan(2_000)
    }
}
