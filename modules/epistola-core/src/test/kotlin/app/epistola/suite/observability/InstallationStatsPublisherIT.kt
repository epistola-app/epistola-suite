// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.observability

import app.epistola.suite.testing.IntegrationTestBase
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class InstallationStatsPublisherIT : IntegrationTestBase() {

    @Autowired
    private lateinit var publisher: InstallationStatsPublisher

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @Test
    fun `the lock holder publishes finite installation-wide counts for every entity`() {
        // Single instance in the test → it always wins the advisory lock and
        // takes the leader path, so every gauge gets a real (non-NaN) value.
        publisher.publish()

        InstallationStatsPublisher.Entity.entries.forEach { entity ->
            val gauge = meterRegistry.find("epistola.installation.${entity.metric}").gauge()
            assertThat(gauge).`as`("gauge for ${entity.metric}").isNotNull
            assertThat(gauge!!.value())
                .`as`("count for ${entity.metric}")
                .isNotNaN()
                .isGreaterThanOrEqualTo(0.0)
        }
    }
}
