// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

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
 * A revoked registration is checked again on every interval (so a restore is noticed), and in the
 * end-to-end run each of those checks logged the same warning: 288 a day at the default interval,
 * for as long as nobody registers again. It warns when the connection becomes refused, and repeats
 * at debug.
 */
class SupportRefusalLogTest {
    // A standalone context, as in HeartbeatFailureLogTest: the JVM-global one is shared with tests running concurrently.
    private val loggerContext = LoggerContext()
    private val logger = loggerContext.getLogger(SupportRefusalLogTest::class.java.name) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attachAppender() {
        loggerContext.start()
        logger.level = Level.DEBUG
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

    private fun status(state: StoredConnectionState, clientId: String = "sc_1") = StoredSupportStatus(clientId = clientId, state = state)

    @Test
    fun `becoming refused is a warning`() {
        SupportRefusalLog.log(logger, status(StoredConnectionState.ACTIVE), "sc_1", "invalid_client")

        val event = appender.list.single()
        assertThat(event.level).isEqualTo(Level.WARN)
        assertThat(event.formattedMessage).contains("sc_1").contains("invalid_client")
    }

    @Test
    fun `a first check that is refused is a warning`() {
        SupportRefusalLog.log(logger, null, "sc_1", "invalid_client")

        assertThat(appender.list.single().level).isEqualTo(Level.WARN)
    }

    @Test
    fun `staying refused repeats at debug`() {
        SupportRefusalLog.log(logger, status(StoredConnectionState.REFUSED), "sc_1", "invalid_client")

        val event = appender.list.single()
        assertThat(event.level).isEqualTo(Level.DEBUG)
        assertThat(event.formattedMessage).contains("sc_1")
    }

    @Test
    fun `another registration refused is a warning again`() {
        SupportRefusalLog.log(logger, status(StoredConnectionState.REFUSED, clientId = "sc_old"), "sc_new", "invalid_client")

        assertThat(appender.list.single().level).isEqualTo(Level.WARN)
    }
}
