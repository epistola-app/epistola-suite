// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.BeanCreationException
import org.springframework.boot.diagnostics.FailureAnalyzer
import org.springframework.core.io.support.SpringFactoriesLoader

class SchemaAheadFailureAnalyzerTest {

    @Test
    fun `Spring Boot loads the analyzer, so a refused start is reported rather than dumped`() {
        // Spring Boot's own analyzers take context arguments it supplies at startup; skip those here.
        val analyzers = SpringFactoriesLoader.forDefaultResourceLocation(javaClass.classLoader)
            .load(FailureAnalyzer::class.java, null, SpringFactoriesLoader.FailureHandler.handleMessage { _, _ -> })
        assertThat(analyzers).hasAtLeastOneElementOfType(SchemaAheadFailureAnalyzer::class.java)
    }

    @Test
    fun `reports the cause as the description and the way back as the action`() {
        val refused = SchemaAheadException(description = "The schema is ahead.", action = "Restore a backup.")
        // Startup failures reach Spring Boot wrapped, as when the Flyway initializer bean fails.
        val wrapped = BeanCreationException("flywayInitializer", "Invocation of init method failed", refused)

        val report = checkNotNull(SchemaAheadFailureAnalyzer().analyze(wrapped)) { "the analyzer did not recognise the failure" }

        assertThat(report.description).isEqualTo("The schema is ahead.")
        assertThat(report.action).isEqualTo("Restore a backup.")
        assertThat(report.cause).isSameAs(refused)
    }

    @Test
    fun `the message keeps both parts for log paths that print the exception itself`() {
        val refused = SchemaAheadException(description = "The schema is ahead.", action = "Restore a backup.")
        assertThat(refused.message).isEqualTo("The schema is ahead. Restore a backup.")
    }
}
