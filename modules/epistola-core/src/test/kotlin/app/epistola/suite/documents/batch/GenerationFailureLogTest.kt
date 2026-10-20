// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.batch

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * After a 1.x upgrade every request still queued fails as predating releases. In a test upgrade 400
 * of them logged 400 errors with stack traces; they are one warning line each now, and any other
 * failure is still an error with its cause.
 */
class GenerationFailureLogTest {
    // A standalone context, as in HeartbeatFailureLogTest: the JVM-global one is shared with tests running concurrently.
    private val loggerContext = LoggerContext()
    private val logger = loggerContext.getLogger(GenerationFailureLogTest::class.java.name) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attachAppender() {
        loggerContext.start()
        logger.isAdditive = false
        appender.context = loggerContext
        appender.start()
        logger.addAppender(appender)
    }

    @Test
    fun `a request that predates releases is a warning without a stack trace`() {
        GenerationFailureLog.log(logger, "r1", RequestPredatesReleasesException("Request r1 was accepted before ...; resubmit it"))

        val event = appender.list.single()
        assertThat(event.level).isEqualTo(Level.WARN)
        assertThat(event.throwableProxy).isNull()
        assertThat(event.formattedMessage).contains("r1").contains("resubmit it")
    }

    @Test
    fun `any other failure is an error with its cause`() {
        GenerationFailureLog.log(logger, "r2", IllegalArgumentException("broken"))

        val event = appender.list.single()
        assertThat(event.level).isEqualTo(Level.ERROR)
        assertThat(event.throwableProxy).isNotNull()
    }
}
