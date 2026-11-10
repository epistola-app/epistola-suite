// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.telemetry

import app.epistola.suite.support.connection.SupportConnection
import app.epistola.suite.support.connection.SupportConnectionState
import app.epistola.suite.support.connection.SupportConnectionStatus
import app.epistola.suite.support.connection.SupportConnections
import app.epistola.suite.support.connection.SupportScope
import app.epistola.suite.support.connection.SupportUnavailableException
import app.epistola.suite.testing.FakeSupportRequest
import app.epistola.suite.testing.FakeSupportResponse
import app.epistola.suite.testing.FakeSupportServer
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.common.v1.KeyValue
import org.springframework.web.client.RestClient
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.zip.GZIPInputStream

/** A [SupportConnection] whose scopes, token and OTLP endpoint a test sets directly. */
class FakeSupportConnection(
    @Volatile var otlp: URI? = null,
    @Volatile var token: String? = "tok-1",
    @Volatile var scopes: Set<SupportScope> = emptySet(),
    override val id: java.util.UUID = SupportConnections.DISCOVERED_ID,
) : SupportConnection {
    fun grant() {
        scopes = setOf(SupportScope.TELEMETRY)
    }

    fun withdraw() {
        scopes = emptySet()
    }

    override fun status(): SupportConnectionStatus = SupportConnectionStatus(
        if (scopes.isEmpty()) SupportConnectionState.Pending("sc_test", "ABCD-EFGH") else SupportConnectionState.Active("sc_test", scopes),
        null,
        null,
        null,
    )

    override fun hasScope(scope: SupportScope): Boolean = scope in scopes

    override fun isSelected(scope: SupportScope): Boolean = true

    /** The scopes the exporters reported refused, in order. */
    val refused: MutableList<SupportScope> = java.util.concurrent.CopyOnWriteArrayList()

    /** Like the real connection: a refused scope reads as not granted until a test grants it again. */
    override fun scopeRefused(scope: SupportScope) {
        refused += scope
        scopes = scopes - scope
    }

    /** The tokens the exporters reported answered `401`, in order. */
    val rejected: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    override fun tokenRejected(token: String) {
        rejected += token
    }

    /** The tokens the exporters reported accepted, in order. */
    val accepted: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    override fun tokenAccepted(token: String) {
        accepted += token
    }

    override fun apiEndpoint(): URI? = null

    override fun otlpEndpoint(): URI? = otlp

    override fun cachedAccessToken(): String? = token

    override fun accessToken(): String = token ?: throw SupportUnavailableException("no token")

    override fun restClient(): RestClient = error("not used by telemetry")

    override fun checkNow() = Unit
}

/** The OTLP side of a [FakeSupportServer]: answers `/otlp/v1/{logs,metrics}` and decodes what arrived. */
class OtlpSink(val server: FakeSupportServer = FakeSupportServer()) : AutoCloseable {
    init {
        server.route("POST", "/otlp/v1/") { FakeSupportResponse(200, ByteArray(0), "application/x-protobuf") }
    }

    val otlpEndpoint: URI get() = URI(server.otlpEndpoint)

    val logRequests: List<FakeSupportRequest> get() = server.requests.filter { it.path == "/otlp/v1/logs" }

    val metricRequests: List<FakeSupportRequest> get() = server.requests.filter { it.path == "/otlp/v1/metrics" }

    override fun close() = server.close()

    companion object {
        fun decodedBody(request: FakeSupportRequest): ByteArray = if (request.header("Content-Encoding").equals("gzip", ignoreCase = true)) {
            GZIPInputStream(ByteArrayInputStream(request.body)).use { it.readAllBytes() }
        } else {
            request.body
        }

        fun logs(request: FakeSupportRequest): ExportLogsServiceRequest = ExportLogsServiceRequest.parseFrom(decodedBody(request))

        fun metrics(request: FakeSupportRequest): ExportMetricsServiceRequest = ExportMetricsServiceRequest.parseFrom(decodedBody(request))

        fun List<KeyValue>.asStrings(): Map<String, String> = associate { it.key to it.value.stringValue }
    }
}

fun testResource(installationId: String = "01928f6e-0000-7000-8000-000000000001"): TelemetryResource = TelemetryResource(
    nodeId = "node-1",
    environment = "test",
    version = "2.0.0-test",
    installationId = { installationId },
)
