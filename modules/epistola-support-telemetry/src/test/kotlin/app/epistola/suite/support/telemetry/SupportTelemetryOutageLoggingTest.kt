// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.telemetry

import app.epistola.suite.support.SupportProperties
import app.epistola.suite.testing.FakeSupportFault
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.micrometer.core.instrument.MockClock
import io.micrometer.registry.otlp.OtlpMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.parallel.Isolated
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.slf4j.LoggerFactory
import org.slf4j.bridge.SLF4JBridgeHandler
import java.net.ServerSocket
import java.net.URI
import java.time.Duration
import java.util.UUID

/**
 * How the telemetry legs log an outage of the support service, an optional and lossy dependency:
 * one WARN when exports start failing and one INFO when they succeed again, per leg; every failure
 * in between at DEBUG; no stack trace for an expected failure (refused, a deadline, `5xx`); and
 * nothing at WARN or above from the OpenTelemetry exporter or Micrometer underneath. Their JUL
 * output is bridged into Logback here as Spring Boot does in the app. Isolated: it captures the
 * root logger, which other test classes running alongside would write to.
 */
@Isolated
class SupportTelemetryOutageLoggingTest {
    /** The ways the support service is not there. */
    enum class Outage {
        REFUSED,
        UNAVAILABLE,
        TIMEOUT,
    }

    private val sink = OtlpSink()
    private val closedPort = ServerSocket(0).use { it.localPort }
    private val connection = FakeSupportConnection(otlp = sink.otlpEndpoint).also { it.grant() }
    private val captured = ListAppender<ILoggingEvent>().apply { start() }
    private val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    private val ownPackage = LoggerFactory.getLogger(OWN_PACKAGE) as Logger
    private var ownLevel: Level? = null
    private val properties = TelemetryProperties(logScheduleDelay = Duration.ofMinutes(5), exportTimeout = Duration.ofSeconds(1), metricStep = Duration.ofMinutes(1))

    private val loggerName = "com.example.telemetryoutage.L${UUID.randomUUID().toString().take(8)}"
    private val appLogger = (LoggerFactory.getLogger(loggerName) as Logger).apply {
        level = Level.TRACE
        isAdditive = false
    }
    private var logs: SupportTelemetryLogs? = null
    private var metrics: SupportTelemetryMeterRegistry? = null
    private val clock = MockClock()

    /** Loggers of the libraries underneath that another test left non-additive, with what to restore. */
    private var detached: List<Pair<Logger, Level?>> = emptyList()

    @BeforeEach
    fun captureLogs() {
        SLF4JBridgeHandler.removeHandlersForRootLogger()
        SLF4JBridgeHandler.install()
        ownLevel = ownPackage.level
        ownPackage.level = Level.DEBUG
        // TelemetryLogAppenderTest gives HttpExporter's logger an appender of its own, non-additive:
        // let every exporter logger reach the root again while this test captures there.
        detached = root.loggerContext.loggerList.filter { (it.name.startsWith("io.opentelemetry") || it.name.startsWith("io.micrometer")) && !it.isAdditive }
            .map { it to it.level }
            .onEach { (logger, _) ->
                logger.isAdditive = true
                logger.level = null
            }
        root.addAppender(captured)
    }

    @AfterEach
    fun tearDown() {
        root.detachAppender(captured)
        detached.forEach { (logger, level) ->
            logger.isAdditive = false
            logger.level = level
        }
        ownPackage.level = ownLevel
        connection.withdraw()
        logs?.stop()
        metrics?.close()
        sink.server.releaseHangs()
        sink.close()
        SLF4JBridgeHandler.uninstall()
    }

    private fun startOutage(outage: Outage, path: String) {
        when (outage) {
            Outage.REFUSED -> connection.otlp = URI("http://127.0.0.1:$closedPort/otlp")
            Outage.UNAVAILABLE -> sink.server.fault(FakeSupportFault.unavailable(), "POST", path)
            Outage.TIMEOUT -> sink.server.fault(FakeSupportFault.Hang(maxMillis = 10_000), "POST", path)
        }
    }

    private fun endOutage() {
        connection.otlp = sink.otlpEndpoint
        sink.server.clearFaults()
        sink.server.releaseHangs()
    }

    private fun events(): List<ILoggingEvent> = synchronized(captured) { captured.list.toList() }

    private fun own(level: Level) = events().filter { it.loggerName.startsWith(OWN_PACKAGE) && it.level == level }

    /** What the leg logged about export outcomes so far: its WARN, its DEBUGs and its INFO. */
    private fun outcomes() = events().count { it.loggerName.startsWith(OWN_PACKAGE) && it.formattedMessage.contains("export") }

    /** Exports finish on the exporter's own thread; waits for the [n]th outcome, at most a few seconds. */
    private fun awaitOutcomes(n: Int) {
        val until = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (outcomes() < n && System.nanoTime() < until) Thread.sleep(20)
    }

    private fun recovered() = own(Level.INFO).any { it.formattedMessage.contains("again") }

    /** Sends with [send] until an export reaches the service again, at most [RECOVERY_WAIT]. */
    private fun awaitRecovery(send: () -> Unit) {
        val until = System.nanoTime() + RECOVERY_WAIT.toNanos()
        while (!recovered() && System.nanoTime() < until) {
            send()
            Thread.sleep(50)
        }
    }

    private fun assertLoggedOneOutage(outage: Outage) {
        val all = events()
        assertThat(all.filter { it.level.isGreaterOrEqual(Level.ERROR) }).`as`("nothing at ERROR for an optional, lossy dependency").isEmpty()
        assertThat(all.filter { it.level == Level.WARN && !it.loggerName.startsWith(OWN_PACKAGE) }.map { "${it.loggerName}: ${it.formattedMessage}" })
            .`as`("nothing at WARN from the exporters underneath").isEmpty()
        val warns = own(Level.WARN)
        assertThat(warns).`as`("one WARN when the outage starts ($outage)").hasSize(1)
        assertThat(warns.single().throwableProxy).`as`("no stack trace for an expected failure").isNull()
        assertThat(own(Level.DEBUG).filter { it.formattedMessage.contains("export") }).`as`("the repeats at DEBUG").hasSizeGreaterThanOrEqualTo(2)
        assertThat(all.filter { it.throwableProxy != null }.map { "${it.loggerName}: ${it.formattedMessage}" }).`as`("no stack trace anywhere").isEmpty()
    }

    @ParameterizedTest
    @EnumSource(Outage::class)
    fun `the logs leg logs an outage once, and its end once`(outage: Outage) {
        val leg = SupportTelemetryLogs(properties, SupportProperties(enabled = true), connection, testResource()).also { it.attach(loggerName) }
        logs = leg
        startOutage(outage, "/otlp/v1/logs")

        repeat(3) {
            appLogger.warn("during the outage {}", it)
            leg.flush()
            awaitOutcomes(it + 1)
        }

        assertLoggedOneOutage(outage)
        assertThat(own(Level.INFO).filter { it.formattedMessage.contains("again") }).isEmpty()

        endOutage()
        // The first export after the outage may take a while on a busy machine (for REFUSED it goes to
        // a new endpoint, with a new client): keep sending until one reaches the service, and only then
        // look at what was logged.
        awaitRecovery {
            appLogger.warn("after the outage")
            leg.flush()
        }

        assertThat(own(Level.INFO).filter { it.formattedMessage.contains("again") }).`as`("one INFO when it recovers").hasSize(1)
        assertThat(own(Level.WARN)).hasSize(1)
    }

    @ParameterizedTest
    @EnumSource(Outage::class)
    fun `the metrics leg logs an outage once, and its end once`(outage: Outage) {
        val registry = SupportTelemetryMeterRegistry.create(properties, SupportOtlpTarget(connection), testResource(), Duration.ofSeconds(1), clock)
        metrics = registry
        startOutage(outage, "/otlp/v1/metrics")

        repeat(3) { publishStep(registry) }

        assertLoggedOneOutage(outage)

        endOutage()
        repeat(2) { publishStep(registry) }

        assertThat(own(Level.INFO).filter { it.formattedMessage.contains("again") }).`as`("one INFO when it recovers").hasSize(1)
        assertThat(own(Level.WARN)).hasSize(1)
        assertThat(sink.metricRequests).isNotEmpty()
    }

    private fun publishStep(registry: SupportTelemetryMeterRegistry) {
        registry.counter("epistola.test.outage").increment()
        clock.add(properties.metricStep)
        OtlpMeterRegistry::class.java.getDeclaredMethod("publish").apply { isAccessible = true }.invoke(registry.otlp)
    }

    private companion object {
        const val OWN_PACKAGE = "app.epistola.suite.support.telemetry"

        /** How long the logs leg may take to get an export through once the outage ends. */
        val RECOVERY_WAIT: Duration = Duration.ofSeconds(30)
    }
}
