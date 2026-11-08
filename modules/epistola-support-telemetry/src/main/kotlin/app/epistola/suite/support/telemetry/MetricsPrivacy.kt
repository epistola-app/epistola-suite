// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.telemetry

import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.metrics.v1.Metric

/**
 * Rewrites a metrics export before it leaves (ADR 0052 §1–2), so the settings in force at export
 * apply, also to meters registered before they changed: the `tenant` tag names the tenant's
 * reference when tenants are confidential, and the `template` tag is dropped when telemetry is
 * pseudonymised. An export that needs neither passes unchanged.
 */
internal object MetricsPrivacy {
    const val TENANT_TAG = "tenant"
    const val TEMPLATE_TAG = "template"

    fun apply(metricsData: ByteArray, privacy: TelemetryPrivacy, confidential: Boolean): ByteArray {
        if (!confidential && !privacy.pseudonymised) return metricsData
        val request = ExportMetricsServiceRequest.newBuilder().mergeFrom(metricsData)
        request.resourceMetricsBuilderList.forEach { resource ->
            resource.scopeMetricsBuilderList.forEach { scope -> scope.metricsBuilderList.forEach { rewrite(it, privacy, confidential) } }
        }
        return request.build().toByteArray()
    }

    private fun rewrite(metric: Metric.Builder, privacy: TelemetryPrivacy, confidential: Boolean) {
        val fix = { attributes: List<KeyValue> -> fixed(attributes, privacy, confidential) }
        when {
            metric.hasGauge() -> metric.gaugeBuilder.dataPointsBuilderList.forEach { p -> fix(p.attributesList)?.let { p.clearAttributes().addAllAttributes(it) } }
            metric.hasSum() -> metric.sumBuilder.dataPointsBuilderList.forEach { p -> fix(p.attributesList)?.let { p.clearAttributes().addAllAttributes(it) } }
            metric.hasHistogram() -> metric.histogramBuilder.dataPointsBuilderList.forEach { p -> fix(p.attributesList)?.let { p.clearAttributes().addAllAttributes(it) } }
            metric.hasExponentialHistogram() -> metric.exponentialHistogramBuilder.dataPointsBuilderList.forEach { p -> fix(p.attributesList)?.let { p.clearAttributes().addAllAttributes(it) } }
            metric.hasSummary() -> metric.summaryBuilder.dataPointsBuilderList.forEach { p -> fix(p.attributesList)?.let { p.clearAttributes().addAllAttributes(it) } }
        }
    }

    /** The attributes rewritten, or null when nothing changes. */
    private fun fixed(attributes: List<KeyValue>, privacy: TelemetryPrivacy, confidential: Boolean): List<KeyValue>? {
        if (attributes.none { it.key == TENANT_TAG || it.key == TEMPLATE_TAG }) return null
        return attributes.mapNotNull { kv ->
            when {
                kv.key == TEMPLATE_TAG && privacy.pseudonymised -> null

                kv.key == TENANT_TAG && confidential ->
                    kv.toBuilder().setValue(AnyValue.newBuilder().setStringValue(privacy.tenantOnWire(kv.value.stringValue))).build()

                else -> kv
            }
        }
    }
}
