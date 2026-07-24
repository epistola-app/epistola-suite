// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.telemetry

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration for the dedicated OTLP telemetry leg that forwards application logs and metrics to
 * epistola-hub. The leg is **off by default** and, on top of [enabled], also requires
 * `epistola.support.enabled=true` and an installation-wide `support-telemetry` entitlement before
 * anything is shipped (see ADR 0006). It is a deliberately separate, isolated OTLP exporter the
 * suite owns — distinct from any bring-your-own OpenTelemetry agent / `management.otlp.*` self-export
 * leg, so customer-side observability never double-exports.
 */
@ConfigurationProperties(prefix = "epistola.support.telemetry")
data class TelemetryProperties(
    /**
     * Master switch for the hub telemetry leg. **On by default** — enabling the support tier
     * (`epistola.support.enabled`) opts every support feature in; the hub's `support-telemetry`
     * entitlement is the real gate on whether anything actually ships. Set to `false` to opt out.
     */
    val enabled: Boolean = true,
    /** Forward application-log events to the hub. */
    val logs: Boolean = true,
    /** Forward metrics to the hub. */
    val metrics: Boolean = true,
    /** Minimum log level forwarded (ERROR/WARN/INFO/DEBUG/TRACE). Bounds volume; default INFO. */
    val logLevel: String = "INFO",
    /** OTLP batch processor: max records buffered before drop-on-overflow. */
    val logMaxQueueSize: Int = 10_000,
    /** OTLP batch processor: max records per export request. */
    val logMaxBatchSize: Int = 512,
    /** OTLP batch processor: how long to wait before flushing a partial batch. */
    val logScheduleDelay: Duration = Duration.ofSeconds(5),
    /** OTLP exporter request timeout. */
    val exportTimeout: Duration = Duration.ofSeconds(30),
    /** Metrics push interval. */
    val metricStep: Duration = Duration.ofSeconds(60),
    /**
     * Strip the per-tenant `tenant` tag from forwarded metrics. Metrics are installation-wide
     * operational telemetry; dropping the only per-tenant dimension keeps the hub feed
     * data-residency-friendly. The local Prometheus / self-export leg is unaffected.
     */
    val stripTenantTagFromMetrics: Boolean = true,
)
