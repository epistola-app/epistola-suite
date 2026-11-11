// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing.logging

import org.springframework.boot.logging.LoggingSystem
import org.springframework.boot.logging.logback.LogbackLoggingSystem

/**
 * Spring Boot's Logback logging system, except that closing a context leaves logging configured.
 *
 * Spring Boot assumes one application per JVM: when a top-level context closes, its logging
 * lifecycle runs `cleanUp()`, which marks Logback uninitialised, so the next boot configures it again
 * from scratch. A test JVM boots and closes many contexts while others run (the migration launcher's,
 * and every test context once its last class is done), and each fresh configuration resets the
 * shared `LoggerContext`: it detaches the appenders other contexts attached (the application-log
 * ingestor's), and two overlapping ones fail a boot with "Logback configuration error detected".
 *
 * With [cleanUp] doing nothing, Logback is configured by the first boot and every later boot finds
 * it initialised and leaves it alone. Log levels a context sets still apply. Selected for the test
 * JVM by [install], from `TestRuntimeLifecycleListener` before any test runs.
 */
class SharedLogbackLoggingSystem(classLoader: ClassLoader) : LogbackLoggingSystem(classLoader) {
    override fun cleanUp() {
        // Deliberately nothing: see the class comment.
    }

    companion object {
        /** Makes Spring Boot use this logging system, unless a run has chosen one itself. */
        fun install() {
            if (System.getProperty(LoggingSystem.SYSTEM_PROPERTY).isNullOrEmpty()) {
                System.setProperty(LoggingSystem.SYSTEM_PROPERTY, SharedLogbackLoggingSystem::class.java.name)
            }
        }
    }
}
