// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.telemetry

import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.metrics.v1.Gauge
import io.opentelemetry.proto.metrics.v1.Metric
import io.opentelemetry.proto.metrics.v1.NumberDataPoint
import io.opentelemetry.proto.metrics.v1.ResourceMetrics
import io.opentelemetry.proto.metrics.v1.ScopeMetrics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MetricsPrivacyTest {
    private fun kv(key: String, value: String) = KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build()

    private val export = ExportMetricsServiceRequest.newBuilder().addResourceMetrics(
        ResourceMetrics.newBuilder().addScopeMetrics(
            ScopeMetrics.newBuilder().addMetrics(
                Metric.newBuilder().setName("epistola.generation.document.duration").setGauge(
                    Gauge.newBuilder().addDataPoints(
                        NumberDataPoint.newBuilder().setAsInt(1).addAttributes(kv("tenant", "gemeente-x")).addAttributes(kv("template", "ontslag-jan")).addAttributes(kv("outcome", "success")),
                    ),
                ),
            ),
        ),
    ).build().toByteArray()

    private fun attributes(data: ByteArray) = ExportMetricsServiceRequest.parseFrom(data).getResourceMetrics(0).getScopeMetrics(0).getMetrics(0).gauge.getDataPoints(0)
        .attributesList.associate { it.key to it.value.stringValue }

    @Test
    fun `confidential and pseudonymised, the tenant is its reference and the template tag is gone`() {
        val rewritten = MetricsPrivacy.apply(export, TelemetryPrivacy(pseudonymised = true, tenantOnWire = { "t-ref" }), confidential = true)

        assertThat(attributes(rewritten)).isEqualTo(mapOf("tenant" to "t-ref", "outcome" to "success"))
    }

    @Test
    fun `named and everything, the export passes unchanged`() {
        assertThat(MetricsPrivacy.apply(export, TelemetryPrivacy.FULL, confidential = false)).isSameAs(export)
    }
}
