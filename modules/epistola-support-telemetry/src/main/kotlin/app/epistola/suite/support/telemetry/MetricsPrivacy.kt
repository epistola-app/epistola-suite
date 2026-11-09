// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.telemetry

import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.metrics.v1.Metric

/**
 * Rewrites a metrics export before it leaves (ADR 0052 §1), so the settings in force at export apply,
 * also to meters registered before they changed: the `tenant` tag names the tenant's reference when
 * tenants are confidential, or goes when that reference is not known yet. An export of named tenants
 * passes unchanged. (The `template` tag never reaches this registry: [SupportTelemetryMeterRegistry]
 * drops it before aggregation.)
 */
internal object MetricsPrivacy {
    private const val TENANT_TAG = SupportTelemetryMeterRegistry.TENANT_TAG

    fun apply(metricsData: ByteArray, privacy: TelemetryPrivacy, confidential: Boolean): ByteArray {
        if (!confidential) return metricsData
        val request = ExportMetricsServiceRequest.newBuilder().mergeFrom(metricsData)
        request.resourceMetricsBuilderList.forEach { resource ->
            resource.scopeMetricsBuilderList.forEach { scope -> scope.metricsBuilderList.forEach { rewrite(it, privacy) } }
        }
        return request.build().toByteArray()
    }

    private fun rewrite(metric: Metric.Builder, privacy: TelemetryPrivacy) {
        val fix = { attributes: List<KeyValue> -> fixed(attributes, privacy) }
        when {
            metric.hasGauge() -> metric.gaugeBuilder.dataPointsBuilderList.forEach { p -> fix(p.attributesList)?.let { p.clearAttributes().addAllAttributes(it) } }
            metric.hasSum() -> metric.sumBuilder.dataPointsBuilderList.forEach { p -> fix(p.attributesList)?.let { p.clearAttributes().addAllAttributes(it) } }
            metric.hasHistogram() -> metric.histogramBuilder.dataPointsBuilderList.forEach { p -> fix(p.attributesList)?.let { p.clearAttributes().addAllAttributes(it) } }
            metric.hasExponentialHistogram() -> metric.exponentialHistogramBuilder.dataPointsBuilderList.forEach { p -> fix(p.attributesList)?.let { p.clearAttributes().addAllAttributes(it) } }
            metric.hasSummary() -> metric.summaryBuilder.dataPointsBuilderList.forEach { p -> fix(p.attributesList)?.let { p.clearAttributes().addAllAttributes(it) } }
        }
    }

    /** The attributes rewritten, or null when nothing changes. */
    private fun fixed(attributes: List<KeyValue>, privacy: TelemetryPrivacy): List<KeyValue>? {
        if (attributes.none { it.key == TENANT_TAG }) return null
        return attributes.mapNotNull { kv ->
            if (kv.key != TENANT_TAG) return@mapNotNull kv
            // No reference known yet: the tag goes rather than the key.
            privacy.tenantOnWire(kv.value.stringValue)?.let { kv.toBuilder().setValue(AnyValue.newBuilder().setStringValue(it)).build() }
        }
    }
}
