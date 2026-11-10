// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.telemetry

import app.epistola.suite.support.SupportProperties
import app.epistola.suite.support.connection.SupportConnection
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.registry.otlp.OtlpConfig
import io.micrometer.registry.otlp.OtlpMeterRegistry
import io.micrometer.registry.otlp.OtlpMetricsSender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * How the support registry joins Spring Boot's metrics: as one more registry under Boot's primary
 * composite, so the app's meters reach it, without exposing a bean of a type that would switch off
 * Boot's own `management.otlp.metrics` export. A plain registry stands in for Prometheus, the
 * app's usual only registry.
 */
class SupportTelemetryMeterWiringTest {
    private val base = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration::class.java, CompositeMeterRegistryAutoConfiguration::class.java))
        .withUserConfiguration(SupportTelemetryConfiguration::class.java)
        .withBean("prometheusStandIn", SimpleMeterRegistry::class.java, { SimpleMeterRegistry() })
        .withBean(SupportProperties::class.java, { SupportProperties(enabled = true) })
        .withBean(TelemetryResource::class.java, { testResource() })

    /** One connection selected for telemetry. */
    private val runner = base.withBean(SupportTelemetryTargets::class.java, { SupportTelemetryTargets { listOf(SupportOtlpTarget(FakeSupportConnection())) } })

    @Test
    fun `app meters reach the support registry through Boot's primary composite`() {
        runner.withPropertyValues("epistola.support.enabled=true").run { context ->
            val primary = context.getBean(MeterRegistry::class.java)
            val support = context.getBean(SupportTelemetryMeterRegistry::class.java)
            assertThat(primary).isInstanceOf(CompositeMeterRegistry::class.java)
            assertThat((primary as CompositeMeterRegistry).registries).contains(support, context.getBean("prometheusStandIn", MeterRegistry::class.java))

            primary.counter("epistola.app.meter", "tenant", "acme", "outcome", "ok").increment()

            val reached = support.otlp.find("epistola.app.meter").counter()
            assertThat(reached).isNotNull
            assertThat(reached!!.id.getTag("outcome")).isEqualTo("ok")
            assertThat(reached.id.getTag("tenant")).isNull()
            // The local registry keeps the tenant tag: stripping is the support leg's alone.
            assertThat(context.getBean("prometheusStandIn", MeterRegistry::class.java).find("epistola.app.meter").tag("tenant", "acme").counter()).isNotNull
        }
    }

    @Test
    fun `exposes no bean that would switch off Boot's own OTLP export`() {
        runner.withPropertyValues("epistola.support.enabled=true").run { context ->
            assertThat(context).hasSingleBean(SupportTelemetryMeterRegistry::class.java)
            assertThat(context.getBeansOfType(OtlpMeterRegistry::class.java)).isEmpty()
            assertThat(context.getBeansOfType(OtlpMetricsSender::class.java)).isEmpty()
            assertThat(context.getBeansOfType(OtlpConfig::class.java)).isEmpty()
        }
    }

    @Test
    fun `no support registry when telemetry or its metrics are off`() {
        runner.withPropertyValues("epistola.support.telemetry.enabled=false").run {
            assertThat(it).doesNotHaveBean(SupportTelemetryMeterRegistry::class.java)
        }
        runner.withPropertyValues("epistola.support.telemetry.metrics=false").run {
            assertThat(it).doesNotHaveBean(SupportTelemetryMeterRegistry::class.java)
        }
    }

    @Test
    fun `app meters reach no OTLP registry while no connection is selected for telemetry, and do once one is`() {
        // An installation that shares telemetry with nobody aggregates nothing for it (ADR 0053 step 3i).
        var selected = emptyList<SupportOtlpTarget>()
        base.withBean(SupportTelemetryTargets::class.java, { SupportTelemetryTargets { selected } }).run { context ->
            val primary = context.getBean(MeterRegistry::class.java)
            val support = context.getBean(SupportTelemetryMeterRegistry::class.java)
            primary.counter("epistola.app.idle").increment()
            assertThat(support.otlp.find("epistola.app.idle").counter()).isNull()

            selected = listOf(SupportOtlpTarget(FakeSupportConnection()))
            support.refresh()
            assertThat(support.otlp.find("epistola.app.idle").counter()).`as`("a meter from before is carried over").isNotNull

            selected = emptyList()
            support.refresh()
            primary.counter("epistola.app.later").increment()
            assertThat(support.otlp.find("epistola.app.later").counter()).`as`("detached again").isNull()
        }
    }
}
