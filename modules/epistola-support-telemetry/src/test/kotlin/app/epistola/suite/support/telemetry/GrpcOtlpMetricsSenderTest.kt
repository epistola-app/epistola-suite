package app.epistola.suite.support.telemetry

import io.grpc.MethodDescriptor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GrpcOtlpMetricsSenderTest {
    @Test
    fun `exports to the standard OTLP MetricsService Export method`() {
        assertThat(GrpcOtlpMetricsSender.EXPORT.fullMethodName)
            .isEqualTo("opentelemetry.proto.collector.metrics.v1.MetricsService/Export")
        assertThat(GrpcOtlpMetricsSender.EXPORT.type).isEqualTo(MethodDescriptor.MethodType.UNARY)
    }
}
