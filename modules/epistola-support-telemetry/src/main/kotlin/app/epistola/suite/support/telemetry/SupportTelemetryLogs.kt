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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * The telemetry **logs** leg: a Logback appender on the root logger that forwards application-log
 * events at or above `epistola.support.telemetry.log-level` to every support connection that takes
 * telemetry (`docs/support-protocol.md`, scope `telemetry`; ADR 0053 §1), each through its own batch
 * processor and exporter, converted with that connection's privacy.
 *
 * It is attached whenever telemetry is on, and gated twice on each connection's `telemetry` scope, so
 * a grant, a withdrawal or a connection added takes effect without a restart:
 *
 * - **on the logging thread**, by the outputs [refreshGate] keeps current every ten seconds: a
 *   connection that is pending or does not grant the scope gets nothing, and with none events are
 *   not even converted. The logging thread never asks a connection itself, which may read the database.
 * - **at export**, by [SupportLogRecordExporter], which drops a batch without the scope.
 *
 * Between them, records wait in a bounded batch queue per connection (`log-max-queue-size`) that drops
 * on overflow; nothing accumulates without bound when a connection is unreachable.
 */
@Component
@ConditionalOnBooleanProperty(name = ["epistola.support.telemetry.enabled", "epistola.support.telemetry.logs"], matchIfMissing = true)
class SupportTelemetryLogs @Autowired constructor(
    private val properties: TelemetryProperties,
    private val supportProperties: SupportProperties,
    private val targets: SupportTelemetryTargets,
    private val resource: TelemetryResource,
) {
    /** One connection, the leg of the tests: [connection] with the installation's privacy settings. */
    constructor(
        properties: TelemetryProperties,
        supportProperties: SupportProperties,
        connection: SupportConnection,
        resource: TelemetryResource,
        privacy: SupportPrivacy? = null,
        references: TenantReferences? = null,
    ) : this(
        properties,
        supportProperties,
        SupportOtlpTarget(connection, logPrivacy = { TelemetryPrivacy.of(privacy?.current() ?: SupportPrivacy.STRICTEST, references) }).let { target -> SupportTelemetryTargets { listOf(target) } },
        resource,
    )

    private val log = LoggerFactory.getLogger(javaClass)

    /** One connection's exporter: its provider, and the target whose grant gates it. */
    private class Leg(val target: SupportOtlpTarget, val provider: SdkLoggerProvider) {
        val output = TelemetryLogAppender.Output(provider.get(INSTRUMENTATION_SCOPE), target.logPrivacy)
    }

    /** Every connection's leg, by connection, built when it is first granted; at most one per connection. */
    private val legs = mutableMapOf<java.util.UUID, Leg>()

    /** The outputs events go to now: the legs whose connection grants telemetry. */
    @Volatile private var forwarding: List<TelemetryLogAppender.Output> = emptyList()

    private var appender: TelemetryLogAppender? = null
    private var attachedTo: String? = null

    /** Attaches after startup, once the database the connections read their status from is ready. */
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        attach(Logger.ROOT_LOGGER_NAME)
    }

    /** Whether events are forwarded now: a connection grants `telemetry`. */
    fun isForwarding(): Boolean = forwarding.isNotEmpty()

    /**
     * Re-reads which connections take telemetry, from their cached status: builds the leg of one newly
     * granted, and drops the leg of one that is gone, sending what it still queued for a moment.
     */
    @Scheduled(fixedDelay = GATE_REFRESH_SECONDS, timeUnit = TimeUnit.SECONDS)
    @Synchronized
    fun refreshGate() {
        if (appender == null) return
        val current = targets.targets()
        val gone = legs.keys - current.map { it.connectionId }.toSet()
        gone.forEach { id -> legs.remove(id)?.provider?.shutdown()?.join(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS) }
        val granted = current.filter { it.granted() }.map { target -> legs.getOrPut(target.connectionId) { Leg(target, provider(target)) } }
        val was = forwarding.isNotEmpty()
        forwarding = granted.map { it.output }
        if (forwarding.isNotEmpty() != was) log.info("Support telemetry logs: {}", if (forwarding.isNotEmpty()) "forwarding (telemetry granted)" else "not forwarding (telemetry not granted)")
    }

    private fun provider(target: SupportOtlpTarget): SdkLoggerProvider {
        val exporter = SupportLogRecordExporter(target, properties.exportTimeout, supportProperties.connectTimeout)
        val processor = BatchLogRecordProcessor
            .builder(exporter)
            .setMaxQueueSize(properties.logMaxQueueSize)
            .setMaxExportBatchSize(properties.logMaxBatchSize)
            .setScheduleDelay(properties.logScheduleDelay)
            .setExporterTimeout(properties.exportTimeout)
            .build()
        return SdkLoggerProvider.builder()
            .setResource(resource.otelResource())
            .addLogRecordProcessor(processor)
            .build()
    }

    /** Attaches the appender to [loggerName]; tests use a logger of their own. */
    @Synchronized
    internal fun attach(loggerName: String) {
        if (appender != null) return
        val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: run {
            log.warn("The logger factory is not Logback; support telemetry log forwarding is off")
            return
        }
        val threshold = ThresholdFilter().apply {
            setLevel(Level.toLevel(properties.logLevel, Level.INFO).levelStr)
            start()
        }
        val forwardingAppender = TelemetryLogAppender({ forwarding }).apply {
            this.context = context
            name = APPENDER_NAME
            addFilter(threshold)
            start()
        }
        appender = forwardingAppender
        attachedTo = loggerName
        refreshGate()
        context.getLogger(loggerName).addAppender(forwardingAppender)
        log.info("Support telemetry logs leg attached (level {}); it forwards to each connection that grants telemetry", properties.logLevel)
    }

    /** Sends what is queued, waiting at most the export timeout; for shutdown and tests. */
    fun flush() {
        val providers = synchronized(this) { legs.values.map { it.provider } }
        providers.forEach { it.forceFlush().join(properties.exportTimeout.toMillis(), TimeUnit.MILLISECONDS) }
    }

    @PreDestroy
    @Synchronized
    fun stop() {
        val attached = appender ?: return
        (LoggerFactory.getILoggerFactory() as? LoggerContext)?.getLogger(attachedTo ?: Logger.ROOT_LOGGER_NAME)?.detachAppender(attached)
        attached.stop()
        forwarding = emptyList()
        // Telemetry is lossy by design: what does not go within a moment is dropped, so a slow or
        // unreachable connection never holds up the shutdown.
        legs.values.forEach { it.provider.shutdown().join(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS) }
        legs.clear()
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
