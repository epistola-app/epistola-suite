package app.epistola.suite.support.telemetry

import app.epistola.hub.client.port.InstallationCredentials
import app.epistola.hub.contract.HubHeaders
import app.epistola.hub.contract.OtlpContract
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientInterceptors
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.stub.ClientCalls
import io.grpc.stub.MetadataUtils
import io.micrometer.registry.otlp.OtlpMetricsSender
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI

/**
 * Ships Micrometer's already-serialized OTLP metrics payload (an `ExportMetricsServiceRequest`) to the
 * Hub over **gRPC** — the standard `MetricsService/Export` call — so metrics use the same transport
 * and endpoint as logs (ADR 0006). Micrometer's `OtlpMeterRegistry` only ships an HTTP sender; this
 * plugs into its `metricsSender` extension point. Request and response are marshalled as raw bytes, so
 * no OTLP proto stubs are needed: the payload is forwarded verbatim and the empty response ignored.
 *
 * The registry exists as a Spring bean (so Boot composes it and fans all meters to it), so gating
 * lives here and is evaluated **per publish** via injected functions (which also keeps the sender
 * decoupled and unit-testable against an in-process server): nothing is sent until [credentials]
 * yields an installation API key (registered) and [entitled] is true (installation-wide
 * `support-telemetry`). The Hub's `x-ep-api-key` authenticates the stream. A failed export throws; the
 * `OtlpMeterRegistry` logs it and drops that publish — fail-open, like the log leg.
 */
class GrpcOtlpMetricsSender(
    private val endpoint: () -> String,
    private val credentials: () -> InstallationCredentials?,
    private val entitled: () -> Boolean,
    private val channelFactory: (String) -> ManagedChannel = ::plaintextOrTlsChannel,
) : OtlpMetricsSender,
    AutoCloseable {
    @Volatile private var channel: ManagedChannel? = null

    @Volatile private var authenticated: Channel? = null

    override fun send(request: OtlpMetricsSender.Request) {
        val creds = credentials() ?: return // not registered with the hub yet
        if (!entitled()) return // not entitled
        ClientCalls.blockingUnaryCall(
            channelFor(creds.apiKey),
            EXPORT,
            CallOptions.DEFAULT.withCompression("gzip"),
            request.metricsData,
        )
    }

    private fun channelFor(apiKey: String): Channel {
        authenticated?.let { return it }
        return synchronized(this) {
            authenticated ?: run {
                val raw = channelFactory(endpoint())
                val auth =
                    ClientInterceptors.intercept(raw, MetadataUtils.newAttachHeadersInterceptor(apiKeyMetadata(apiKey)))
                channel = raw
                authenticated = auth
                auth
            }
        }
    }

    override fun close() {
        channel?.shutdown()
    }

    companion object {
        const val SERVICE = OtlpContract.METRICS_SERVICE

        private val BYTES: MethodDescriptor.Marshaller<ByteArray> =
            object : MethodDescriptor.Marshaller<ByteArray> {
                override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)

                override fun parse(stream: InputStream): ByteArray = stream.readAllBytes()
            }

        val EXPORT: MethodDescriptor<ByteArray, ByteArray> =
            MethodDescriptor
                .newBuilder(BYTES, BYTES)
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE, OtlpContract.EXPORT_METHOD))
                .build()

        private fun apiKeyMetadata(apiKey: String): Metadata = Metadata().apply {
            put(Metadata.Key.of(HubHeaders.API_KEY, Metadata.ASCII_STRING_MARSHALLER), apiKey)
        }
    }
}

/** The production channel: plaintext for an `http://` endpoint, TLS for `https://`. */
private fun plaintextOrTlsChannel(endpoint: String): ManagedChannel {
    val uri = URI(endpoint)
    val plaintext = uri.scheme.equals("http", ignoreCase = true)
    val port =
        if (uri.port != -1) {
            uri.port
        } else if (plaintext) {
            80
        } else {
            443
        }
    val builder = ManagedChannelBuilder.forAddress(uri.host, port)
    if (plaintext) builder.usePlaintext()
    return builder.build()
}
