// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.installation.InstallationService
import app.epistola.suite.support.connection.DefaultSupportConnection
import app.epistola.suite.support.connection.SupportConnectionState
import app.epistola.suite.support.connection.SupportConnectionStore
import app.epistola.suite.support.telemetry.SupportTelemetryLogs
import app.epistola.suite.support.telemetry.SupportTelemetryMeterRegistry
import app.epistola.suite.testing.FakeSupportRequest
import app.epistola.suite.testing.FakeSupportResponse
import app.epistola.suite.testing.FakeSupportServer
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import io.micrometer.registry.otlp.OtlpMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.GZIPInputStream

/**
 * The telemetry leg in the running app, against a stand-in support service: nothing leaves while
 * the installation is pending, logs and metrics follow once Epistola grants `telemetry`, a new
 * token is picked up, a revocation stops both — and Spring Boot's own OTLP metrics export, turned
 * on here, keeps working beside it without the support token.
 *
 * Exports run on the exporters' own threads, which read the system clock rather than this test's
 * clock; tokens are issued for ten years so they are valid on both.
 */
@Isolated("Uses the global support connection in app_metadata")
class SupportTelemetryIntegrationTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var store: SupportConnectionStore

    @Autowired
    private lateinit var connection: DefaultSupportConnection

    @Autowired
    private lateinit var logs: SupportTelemetryLogs

    @Autowired
    private lateinit var supportRegistry: SupportTelemetryMeterRegistry

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @Autowired
    private lateinit var otlpRegistries: List<OtlpMeterRegistry>

    @Autowired
    private lateinit var installations: InstallationService

    private val log = LoggerFactory.getLogger(javaClass)

    @BeforeEach
    fun forget() {
        store.forget()
        connection.invalidate()
        support.requests.clear()
    }

    private fun otlp(signal: String): List<FakeSupportRequest> = support.requests.filter { it.path == "/otlp/v1/$signal" }

    private fun exportNow() {
        logs.refreshGate()
        logs.flush()
        publish(supportRegistry.registries.single() as OtlpMeterRegistry)
    }

    private fun logAndExport(message: String) {
        logs.refreshGate()
        log.warn(message)
        exportNow()
    }

    @Test
    fun `Boot's own OTLP export stays on beside the support registry, and app meters reach both`() {
        val bootOtlp = otlpRegistries.single()
        assertThat(meterRegistry).isInstanceOf(CompositeMeterRegistry::class.java)
        assertThat((meterRegistry as CompositeMeterRegistry).registries).contains(supportRegistry, bootOtlp)
        val tenant = "tenant-${UUID.randomUUID()}"
        meterRegistry.counter("epistola.support.telemetry.test", "tenant", tenant).increment()
        grantTelemetry()

        publish(supportRegistry.registries.single() as OtlpMeterRegistry)
        publish(bootOtlp)

        val toSupport = decoded(otlp("metrics").single())
        assertThat(toSupport).contains("epistola.support.telemetry.test").contains("jvm.").doesNotContain(tenant)
        val toBoot = support.requests.single { it.path == "/boot-otlp/v1/metrics" }
        assertThat(decoded(toBoot)).contains("epistola.support.telemetry.test").contains(tenant)
        assertThat(toBoot.header("Authorization")).isNull()
    }

    @Test
    fun `sends nothing while pending, both signals once telemetry is granted, a new token after rotation, and nothing once revoked`() {
        connection.checkNow()
        assertThat(connection.status().state).isInstanceOf(SupportConnectionState.Pending::class.java)
        logAndExport("while pending")
        assertThat(support.requests.filter { it.path.startsWith("/otlp/") }).isEmpty()

        grantTelemetry()
        val granted = "telemetry granted ${UUID.randomUUID()}"
        logAndExport(granted)

        val logRequest = otlp("logs").single { decoded(it).contains(granted) }
        val metricRequest = otlp("metrics").single()
        listOf(logRequest, metricRequest).forEach { request ->
            assertThat(request.header("Content-Type")).isEqualTo("application/x-protobuf")
            assertThat(request.header("Content-Encoding")).isEqualTo("gzip")
            assertThat(request.tokenScopes).contains("telemetry")
            assertThat(decoded(request)).contains("epistola.installation.id").contains(installations.get().id.toString())
        }
        assertThat(decoded(logRequest)).doesNotContain("while pending")
        val firstToken = logRequest.bearerToken

        support.expireTokens()
        connection.checkNow() // the dead token is dropped and a new one fetched
        val rotated = "after rotation ${UUID.randomUUID()}"
        logAndExport(rotated)
        val rotatedRequest = otlp("logs").single { decoded(it).contains(rotated) }
        assertThat(rotatedRequest.bearerToken).isNotNull.isNotEqualTo(firstToken)
        assertThat(rotatedRequest.tokenScopes).contains("telemetry")

        support.revoke()
        connection.checkNow()
        assertThat(connection.status().state).isInstanceOf(SupportConnectionState.Refused::class.java)
        val before = support.requests.count { it.path.startsWith("/otlp/") }
        logAndExport("after revocation")
        assertThat(support.requests.count { it.path.startsWith("/otlp/") }).isEqualTo(before)
        assertThat(logs.isForwarding()).isFalse()
    }

    @Test
    fun `sends nothing when Epistola grants other services but not telemetry`() {
        connection.checkNow()
        support.activate(scopes = setOf("requests"))
        connection.checkNow()
        assertThat(connection.status().state).isInstanceOf(SupportConnectionState.Active::class.java)

        logAndExport("feedback only")

        assertThat(support.requests.filter { it.path.startsWith("/otlp/") }).isEmpty()
    }

    private fun grantTelemetry() {
        connection.checkNow()
        support.activate(scopes = setOf("telemetry"))
        connection.checkNow()
        assertThat(connection.hasScope(app.epistola.suite.support.connection.SupportScope.TELEMETRY)).isTrue()
    }

    companion object {
        private val support = FakeSupportServer().apply {
            tokenLifetimeSeconds = 10L * 365 * 24 * 3600
            route("POST", "/otlp/v1/") { FakeSupportResponse(200, ByteArray(0), "application/x-protobuf") }
            route("POST", "/boot-otlp/v1/") { FakeSupportResponse(200, ByteArray(0), "application/x-protobuf") }
        }

        @JvmStatic
        @DynamicPropertySource
        fun supportService(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.enabled") { "true" }
            registry.add("epistola.support.privacy.tenant-identity") { "named" }
            // Every service selected, as an administrator would; the selection itself is tested in SupportServiceSelectionIntegrationTest.
            registry.add("epistola.support.services") { "telemetry,requests,mirror" }
            // These tests let the check register; without it, an installation administrator registers.
            registry.add("epistola.support.auto-register") { "true" }
            registry.add("epistola.support.endpoint") { support.baseUrl }
            registry.add("epistola.support.allow-http") { "true" }
            registry.add("epistola.support.telemetry.metric-step") { "1h" }
            registry.add("management.otlp.metrics.export.enabled") { "true" }
            registry.add("management.otlp.metrics.export.url") { "${support.baseUrl}/boot-otlp/v1/metrics" }
            registry.add("management.otlp.metrics.export.step") { "1h" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = support.close()

        /** The request body, gunzipped; protobuf keeps strings as plain UTF-8, so text checks work. */
        private fun decoded(request: FakeSupportRequest): String {
            val bytes = if (request.header("Content-Encoding") == "gzip") GZIPInputStream(ByteArrayInputStream(request.body)).use { it.readAllBytes() } else request.body
            return String(bytes, StandardCharsets.ISO_8859_1)
        }

        /** `publish()` is protected; the test publishes on demand instead of waiting a step. */
        private fun publish(registry: OtlpMeterRegistry) {
            OtlpMeterRegistry::class.java.getDeclaredMethod("publish").apply { isAccessible = true }.invoke(registry)
        }
    }
}
