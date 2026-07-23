package app.epistola.suite.cluster

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Verifies the heartbeat log throttle turns a sustained outage into a single
 * WARN (+ recovery note) rather than a per-interval flood. Pure unit test:
 * drives [HeartbeatFailureLog] directly and captures events with a logback
 * [ListAppender] — no Spring context, no database.
 */
class HeartbeatFailureLogTest {
    // Use a standalone context instead of LoggerFactory's JVM-global Logback
    // context. Spring Boot context tests can reinitialize the global context while
    // unit tests run concurrently, detaching ListAppender instances mid-assertion.
    private val loggerContext = LoggerContext()
    private val logger = loggerContext.getLogger(HeartbeatFailureLogTest::class.java.name) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attachAppender() {
        loggerContext.start()
        logger.level = Level.DEBUG // so DEBUG repeats are captured too
        logger.isAdditive = false
        appender.context = loggerContext
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detachAppender() {
        logger.detachAppender(appender)
        appender.stop()
        loggerContext.stop()
    }

    private fun events(level: Level) = appender.list.filter { it.level == level }

    @Test
    fun `sustained outage warns once, repeats at debug, then logs one recovery`() {
        val throttle = HeartbeatFailureLog(logger)
        val boom = RuntimeException("Connection to localhost:32777 refused")

        throttle.recordFailure(boom, shuttingDown = false)
        throttle.recordFailure(boom, shuttingDown = false)
        throttle.recordFailure(boom, shuttingDown = false)
        assertThat(throttle.consecutiveFailures).isEqualTo(3)

        throttle.recordSuccess()

        assertThat(events(Level.WARN)).hasSize(1)
        assertThat(events(Level.WARN).single().formattedMessage)
            .contains("Cluster node heartbeat failed")
        // The first WARN carries the throwable for diagnosis.
        assertThat(events(Level.WARN).single().throwableProxy).isNotNull()
        // Two repeat failures dropped to DEBUG (no stack trace).
        assertThat(events(Level.DEBUG)).hasSize(2)
        assertThat(events(Level.DEBUG)).allSatisfy { assertThat(it.throwableProxy).isNull() }
        // One recovery INFO; counter reset.
        assertThat(events(Level.INFO)).hasSize(1)
        assertThat(events(Level.INFO).single().formattedMessage).contains("recovered after 3")
        assertThat(throttle.consecutiveFailures).isZero()
    }

    @Test
    fun `failures while shutting down never warn`() {
        val throttle = HeartbeatFailureLog(logger)

        throttle.recordFailure(RuntimeException("boom"), shuttingDown = true)
        throttle.recordFailure(RuntimeException("boom"), shuttingDown = true)

        assertThat(events(Level.WARN)).isEmpty()
        assertThat(events(Level.DEBUG)).hasSize(2)
    }

    @Test
    fun `success with no prior failures logs nothing`() {
        val throttle = HeartbeatFailureLog(logger)

        throttle.recordSuccess()

        assertThat(appender.list).isEmpty()
        assertThat(throttle.consecutiveFailures).isZero()
    }

    @Test
    fun `recovery then a new failure warns again`() {
        val throttle = HeartbeatFailureLog(logger)

        throttle.recordFailure(RuntimeException("boom"), shuttingDown = false) // WARN #1
        throttle.recordSuccess() // INFO recovery
        throttle.recordFailure(RuntimeException("boom"), shuttingDown = false) // WARN #2 (new run)

        assertThat(events(Level.WARN)).hasSize(2)
        assertThat(events(Level.INFO)).hasSize(1)
    }
}
