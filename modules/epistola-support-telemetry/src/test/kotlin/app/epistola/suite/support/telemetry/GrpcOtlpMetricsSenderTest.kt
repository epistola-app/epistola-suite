// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.telemetry

import app.epistola.hub.client.port.InstallationCredentials
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerServiceDefinition
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.ServerCalls
import io.micrometer.registry.otlp.OtlpMetricsSender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class GrpcOtlpMetricsSenderTest {
    @Test
    fun `exports to the standard OTLP MetricsService Export method`() {
        assertThat(GrpcOtlpMetricsSender.EXPORT.fullMethodName)
            .isEqualTo("opentelemetry.proto.collector.metrics.v1.MetricsService/Export")
    }

    @Test
    fun `sends the payload over gRPC with the api key when registered and entitled`() {
        val receivedBody = AtomicReference<ByteArray?>()
        val receivedApiKey = AtomicReference<String?>()
        val server =
            InProcessServerBuilder
                .forName("metrics-ok")
                .directExecutor()
                .addService(captureService(receivedBody))
                .intercept(captureApiKey(receivedApiKey))
                .build()
                .start()

        val sender = senderTo("metrics-ok", credentials = { CREDS }, entitled = { true })
        sender.send(request("payload".toByteArray()))

        assertThat(receivedBody.get()).isEqualTo("payload".toByteArray())
        assertThat(receivedApiKey.get()).isEqualTo("ek_test")

        sender.close()
        server.shutdownNow()
    }

    @Test
    fun `does not send when the installation is not registered`() {
        val called = AtomicBoolean(false)
        val server = InProcessServerBuilder.forName("metrics-noreg").directExecutor().addService(flagService(called)).build().start()

        senderTo("metrics-noreg", credentials = { null }, entitled = { true }).send(request("x".toByteArray()))

        assertThat(called).isFalse()
        server.shutdownNow()
    }

    @Test
    fun `does not send when the installation is not entitled`() {
        val called = AtomicBoolean(false)
        val server = InProcessServerBuilder.forName("metrics-noent").directExecutor().addService(flagService(called)).build().start()

        senderTo("metrics-noent", credentials = { CREDS }, entitled = { false }).send(request("x".toByteArray()))

        assertThat(called).isFalse()
        server.shutdownNow()
    }

    // --- helpers ---

    private fun senderTo(
        name: String,
        credentials: () -> InstallationCredentials?,
        entitled: () -> Boolean,
    ) = GrpcOtlpMetricsSender(
        endpoint = { "in-process" },
        credentials = credentials,
        entitled = entitled,
        channelFactory = { InProcessChannelBuilder.forName(name).directExecutor().build() },
    )

    private fun request(body: ByteArray): OtlpMetricsSender.Request = OtlpMetricsSender.Request.builder(body).build()

    /** An OTLP MetricsService whose Export records the request body. */
    private fun captureService(body: AtomicReference<ByteArray?>): ServerServiceDefinition = ServerServiceDefinition
        .builder(GrpcOtlpMetricsSender.SERVICE)
        .addMethod(
            GrpcOtlpMetricsSender.EXPORT,
            ServerCalls.asyncUnaryCall { req, responseObserver ->
                body.set(req)
                responseObserver.onNext(ByteArray(0))
                responseObserver.onCompleted()
            },
        ).build()

    /** An OTLP MetricsService whose Export just flags that it was called. */
    private fun flagService(called: AtomicBoolean): ServerServiceDefinition = ServerServiceDefinition
        .builder(GrpcOtlpMetricsSender.SERVICE)
        .addMethod(
            GrpcOtlpMetricsSender.EXPORT,
            ServerCalls.asyncUnaryCall { _, responseObserver ->
                called.set(true)
                responseObserver.onNext(ByteArray(0))
                responseObserver.onCompleted()
            },
        ).build()

    private fun captureApiKey(key: AtomicReference<String?>): ServerInterceptor = object : ServerInterceptor {
        override fun <ReqT, RespT> interceptCall(
            call: ServerCall<ReqT, RespT>,
            headers: Metadata,
            next: ServerCallHandler<ReqT, RespT>,
        ): ServerCall.Listener<ReqT> {
            key.set(headers.get(Metadata.Key.of("x-ep-api-key", Metadata.ASCII_STRING_MARSHALLER)))
            return next.startCall(call, headers)
        }
    }

    private companion object {
        val CREDS = InstallationCredentials(UUID.randomUUID(), "ek_test")
    }
}
