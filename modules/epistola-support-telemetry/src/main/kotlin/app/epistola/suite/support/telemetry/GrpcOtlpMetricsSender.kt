package app.epistola.suite.support.telemetry

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientInterceptors
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.stub.ClientCalls
import io.grpc.stub.MetadataUtils
import io.micrometer.registry.otlp.OtlpMetricsSender
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Ships Micrometer's already-serialized OTLP metrics payload (an `ExportMetricsServiceRequest`) to the
 * Hub over **gRPC** — the standard `MetricsService/Export` call — so metrics use the same transport
 * and endpoint as logs (ADR 0006). Micrometer's `OtlpMeterRegistry` only ships an HTTP sender; this
 * plugs into its `metricsSender` extension point. Request and response are marshalled as raw bytes, so
 * no OTLP proto stubs are needed: the payload is forwarded verbatim and the empty response ignored.
 * The installation API key is attached as `x-ep-api-key` metadata, matching the Hub's gRPC auth.
 *
 * A failed export throws (the Hub is unreachable, or rejects an unauthenticated/unentitled call); the
 * `OtlpMeterRegistry` catches and logs it and drops that publish — fail-open, like the log leg.
 */
class GrpcOtlpMetricsSender(
    channel: ManagedChannel,
    apiKey: String,
) : OtlpMetricsSender {
    private val authenticated: Channel =
        ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(apiKeyMetadata(apiKey)))

    override fun send(request: OtlpMetricsSender.Request) {
        ClientCalls.blockingUnaryCall(
            authenticated,
            EXPORT,
            CallOptions.DEFAULT.withCompression("gzip"),
            request.metricsData,
        )
    }

    companion object {
        const val SERVICE = "opentelemetry.proto.collector.metrics.v1.MetricsService"

        private val BYTES: MethodDescriptor.Marshaller<ByteArray> =
            object : MethodDescriptor.Marshaller<ByteArray> {
                override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)

                override fun parse(stream: InputStream): ByteArray = stream.readAllBytes()
            }

        val EXPORT: MethodDescriptor<ByteArray, ByteArray> =
            MethodDescriptor
                .newBuilder(BYTES, BYTES)
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE, "Export"))
                .build()

        private fun apiKeyMetadata(apiKey: String): Metadata = Metadata().apply {
            put(Metadata.Key.of("x-ep-api-key", Metadata.ASCII_STRING_MARSHALLER), apiKey)
        }
    }
}
