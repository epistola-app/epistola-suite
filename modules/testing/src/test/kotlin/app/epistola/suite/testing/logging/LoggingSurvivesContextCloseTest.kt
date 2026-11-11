// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.Banner
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Configuration

/**
 * A test JVM boots and closes many Spring Boot contexts while others run. Spring Boot tears its
 * logging down when a context closes, so the next boot configures Logback again from scratch:
 * a reset that detaches every appender other contexts attached (the application-log ingestor's)
 * and that, overlapping another boot doing the same, fails it with "Logback configuration error
 * detected". In tests, logging is configured once and stays.
 */
class LoggingSurvivesContextCloseTest {
    @Configuration(proxyBeanMethods = false)
    class Empty

    private fun boot() = SpringApplicationBuilder(Empty::class.java)
        .web(WebApplicationType.NONE)
        .bannerMode(Banner.Mode.OFF)
        .run()

    @Test
    fun `closing one Spring Boot context leaves logging as the others set it up`() {
        boot().close()
        val root = (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger(Logger.ROOT_LOGGER_NAME)
        val attached = ListAppender<ILoggingEvent>().apply {
            name = "attached-by-another-context"
            start()
        }
        root.addAppender(attached)
        try {
            boot().close()

            assertThat(root.getAppender("attached-by-another-context")).`as`("an appender another context attached").isNotNull
        } finally {
            root.detachAppender(attached)
        }
    }
}
