// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.telemetry

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * The telemetry leg that forwards application logs and metrics to the Epistola support service over
 * OTLP/HTTP (`docs/support-protocol.md`, scope `telemetry`). On top of [enabled] it requires
 * `epistola.support.enabled=true`; then, per export, the `telemetry` scope Epistola grants the
 * installation. While the installation is pending or the scope is not granted nothing is sent, and
 * a grant or withdrawal takes effect without a restart.
 *
 * It is a separate, isolated exporter the suite owns — distinct from any bring-your-own
 * OpenTelemetry agent and from Spring Boot's `management.otlp.*` export, which stay the customer's
 * and keep working beside it.
 */
@ConfigurationProperties(prefix = "epistola.support.telemetry")
data class TelemetryProperties(
    /**
     * Master switch for the telemetry leg. **On by default** — enabling the support connection
     * (`epistola.support.enabled`) opts it in; the `telemetry` scope Epistola grants is the real
     * gate on whether anything actually ships. Set to `false` to opt out.
     */
    val enabled: Boolean = true,
    /** Forward application-log events to the support service. */
    val logs: Boolean = true,
    /** Forward metrics to the support service. */
    val metrics: Boolean = true,
    /** Minimum log level forwarded (ERROR/WARN/INFO/DEBUG/TRACE). Bounds volume; default INFO. */
    val logLevel: String = "INFO",
    /**
     * OTLP batch processor: max records buffered before drop-on-overflow. With every record cut to
     * a few KiB ([TelemetryLogAppender.MAX_BODY_CHARS], [TelemetryLogAppender.MAX_STACKTRACE_CHARS]),
     * the default keeps a full queue to some tens of MB.
     */
    val logMaxQueueSize: Int = 2_048,
    /** OTLP batch processor: max records per export request. */
    val logMaxBatchSize: Int = 512,
    /** OTLP batch processor: how long to wait before flushing a partial batch. */
    val logScheduleDelay: Duration = Duration.ofSeconds(5),
    /** Timeout of one export request, logs or metrics. */
    val exportTimeout: Duration = Duration.ofSeconds(30),
    /** Metrics push interval. */
    val metricStep: Duration = Duration.ofSeconds(60),
    /**
     * Strip the per-tenant `tenant` tag from forwarded metrics. Metrics are installation-wide
     * operational telemetry; dropping the only per-tenant dimension keeps the support feed
     * data-residency-friendly. The local Prometheus / self-export leg is unaffected.
     */
    val stripTenantTagFromMetrics: Boolean = true,
)
