// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.telemetry

import app.epistola.suite.support.SupportProperties
import app.epistola.suite.support.connection.SupportConnection
import app.epistola.suite.support.privacy.SupportPrivacy
import app.epistola.suite.support.privacy.TenantReferences
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.filter.ThresholdFilter
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * The telemetry **logs** leg: a Logback appender on the root logger that forwards application-log
 * events at or above `epistola.support.telemetry.log-level` to the support service
 * (`docs/support-protocol.md`, scope `telemetry`).
 *
 * It is attached whenever the support connection and telemetry are on, and gated twice on the
 * `telemetry` scope, so a grant or a withdrawal takes effect without a restart:
 *
 * - **on the logging thread**, by a flag [refreshGate] keeps current every ten seconds: while the
 *   installation is pending or the scope is not granted, events are not even converted. The
 *   logging thread never asks the connection itself, which may read the database.
 * - **at export**, by [SupportLogRecordExporter], which drops a batch without the scope.
 *
 * Between them, records wait in a bounded batch queue (`log-max-queue-size`) that drops on
 * overflow; nothing accumulates without bound when the support service is unreachable.
 */
@Component
@ConditionalOnBooleanProperty("epistola.support.enabled")
@ConditionalOnBooleanProperty(name = ["epistola.support.telemetry.enabled", "epistola.support.telemetry.logs"], matchIfMissing = true)
class SupportTelemetryLogs(
    private val properties: TelemetryProperties,
    private val supportProperties: SupportProperties,
    connection: SupportConnection,
    private val resource: TelemetryResource,
    private val privacy: SupportPrivacy? = null,
    private val references: TenantReferences? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val target = SupportOtlpTarget(connection)

    @Volatile private var forwarding = false

    private var provider: SdkLoggerProvider? = null
    private var appender: TelemetryLogAppender? = null
    private var attachedTo: String? = null

    /** Attaches after startup, once the database the connection reads its status from is ready. */
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        attach(Logger.ROOT_LOGGER_NAME)
    }

    /** Whether events are forwarded now: the support service grants `telemetry`. */
    fun isForwarding(): Boolean = forwarding

    /** Re-reads the `telemetry` scope from the connection's cached status. */
    @Scheduled(fixedDelay = GATE_REFRESH_SECONDS, timeUnit = TimeUnit.SECONDS)
    fun refreshGate() {
        val granted = target.granted()
        if (granted != forwarding) log.info("Support telemetry logs: {}", if (granted) "forwarding (telemetry granted)" else "not forwarding (telemetry not granted)")
        forwarding = granted
    }

    /** Attaches the appender to [loggerName]; tests use a logger of their own. */
    @Synchronized
    internal fun attach(loggerName: String) {
        if (appender != null) return
        val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: run {
            log.warn("The logger factory is not Logback; support telemetry log forwarding is off")
            return
        }
        val exporter = SupportLogRecordExporter(target, properties.exportTimeout, supportProperties.connectTimeout)
        val processor = BatchLogRecordProcessor
            .builder(exporter)
            .setMaxQueueSize(properties.logMaxQueueSize)
            .setMaxExportBatchSize(properties.logMaxBatchSize)
            .setScheduleDelay(properties.logScheduleDelay)
            .setExporterTimeout(properties.exportTimeout)
            .build()
        val loggerProvider = SdkLoggerProvider.builder()
            .setResource(resource.otelResource())
            .addLogRecordProcessor(processor)
            .build()
        val threshold = ThresholdFilter().apply {
            setLevel(Level.toLevel(properties.logLevel, Level.INFO).levelStr)
            start()
        }
        val forwardingAppender = TelemetryLogAppender(loggerProvider.get(INSTRUMENTATION_SCOPE), { forwarding }, ::telemetryPrivacy).apply {
            this.context = context
            name = APPENDER_NAME
            addFilter(threshold)
            start()
        }
        refreshGate()
        context.getLogger(loggerName).addAppender(forwardingAppender)
        provider = loggerProvider
        appender = forwardingAppender
        attachedTo = loggerName
        log.info("Support telemetry logs leg attached (level {}); it forwards while Epistola grants telemetry", properties.logLevel)
    }

    private fun telemetryPrivacy(): TelemetryPrivacy {
        // Without the settings, the strictest apply.
        return TelemetryPrivacy.of(privacy?.current() ?: SupportPrivacy.STRICTEST, references)
    }

    /** Sends what is queued, waiting at most the export timeout; for shutdown and tests. */
    fun flush() {
        provider?.forceFlush()?.join(properties.exportTimeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    @PreDestroy
    @Synchronized
    fun stop() {
        val attached = appender ?: return
        (LoggerFactory.getILoggerFactory() as? LoggerContext)?.getLogger(attachedTo ?: Logger.ROOT_LOGGER_NAME)?.detachAppender(attached)
        attached.stop()
        // Telemetry is lossy by design: what does not go within a moment is dropped, so a slow or
        // unreachable support service never holds up the shutdown.
        provider?.shutdown()?.join(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)
        provider = null
        appender = null
        attachedTo = null
    }

    private companion object {
        const val GATE_REFRESH_SECONDS = 10L

        /** The most stopping waits for queued records to reach the support service. */
        val SHUTDOWN_WAIT: java.time.Duration = java.time.Duration.ofSeconds(1)
        const val INSTRUMENTATION_SCOPE = "app.epistola.suite"
        const val APPENDER_NAME = "EPISTOLA_SUPPORT_TELEMETRY"
    }
}
