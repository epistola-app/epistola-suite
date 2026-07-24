// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.observability

import app.epistola.suite.documents.JobPollingProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status

class JobPollerHealthIndicatorTest {

    // interval 5000ms * STALE_MULTIPLIER (4) => 20_000ms staleness threshold.
    private val indicator = JobPollerHealthIndicator(
        jobPoller = null,
        pollingProperties = JobPollingProperties(intervalMs = 5_000),
    )

    @Test
    fun `UP when the last poll is within the staleness threshold`() {
        assertThat(indicator.evaluate(1_000).status).isEqualTo(Status.UP)
        assertThat(indicator.evaluate(20_000).status).isEqualTo(Status.UP)
    }

    @Test
    fun `DOWN when the last poll is older than the threshold`() {
        val health = indicator.evaluate(25_000)
        assertThat(health.status).isEqualTo(Status.DOWN)
        assertThat(health.details).containsEntry("lastPollAgeMs", 25_000L)
        assertThat(health.details).containsEntry("staleThresholdMs", 20_000L)
    }

    @Test
    fun `UP with a disabled note when polling is off (no poller bean)`() {
        val health = indicator.health()
        assertThat(health.status).isEqualTo(Status.UP)
        assertThat(health.details).containsEntry("polling", "disabled")
    }
}
