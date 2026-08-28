// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.cluster

import app.epistola.suite.cluster.ClusterProperties.Companion.DEFAULT_CAPABILITY
import app.epistola.suite.cluster.ClusterProperties.Companion.PDF_RENDER_CAPABILITY
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The advertised capability set is what routes render jobs: [ClusterProperties.PDF_RENDER_CAPABILITY]
 * gates the JobPoller/StaleJobRecovery tasks, so folding it in (or out) here is exactly what
 * makes the suite render by default, lets an operator turn rendering off, and lets a dedicated
 * apps/pdfrender worker advertise render-only.
 */
class ClusterPropertiesTest {

    @Test
    fun `suite renders by default — configured suite capability gains render`() {
        val effective = ClusterProperties(capabilities = listOf(DEFAULT_CAPABILITY))
            .normalizedCapabilities(pdfRenderEnabled = true)

        assertThat(effective).containsExactlyInAnyOrder(DEFAULT_CAPABILITY, PDF_RENDER_CAPABILITY)
    }

    @Test
    fun `pdf-render disabled drops the capability so a suite node becomes control-plane only`() {
        val effective = ClusterProperties(capabilities = listOf(DEFAULT_CAPABILITY))
            .normalizedCapabilities(pdfRenderEnabled = false)

        assertThat(effective).containsExactly(DEFAULT_CAPABILITY)
    }

    @Test
    fun `a render-only worker advertises just pdf-render`() {
        val effective = ClusterProperties(capabilities = listOf(PDF_RENDER_CAPABILITY))
            .normalizedCapabilities(pdfRenderEnabled = true)

        assertThat(effective).containsExactly(PDF_RENDER_CAPABILITY)
    }

    @Test
    fun `render is not duplicated when already configured`() {
        val effective = ClusterProperties(capabilities = listOf(DEFAULT_CAPABILITY, PDF_RENDER_CAPABILITY))
            .normalizedCapabilities(pdfRenderEnabled = true)

        assertThat(effective).containsExactlyInAnyOrder(DEFAULT_CAPABILITY, PDF_RENDER_CAPABILITY)
    }

    @Test
    fun `blank capabilities fall back to the default before folding in render`() {
        val effective = ClusterProperties(capabilities = listOf(" ", ""))
            .normalizedCapabilities(pdfRenderEnabled = true)

        assertThat(effective).containsExactlyInAnyOrder(DEFAULT_CAPABILITY, PDF_RENDER_CAPABILITY)
    }

    /**
     * Purging a `cluster_nodes` row makes that node stop vouching for its scheduled-task
     * definitions immediately, because `LIVE_REGISTRATION_EXISTS` inner-joins registrations
     * to the node row. Retention shorter than the reconciliation grace period would let the
     * reaper retire definitions earlier than the reconciler would — so the clamp, not the
     * configured value, is what the reaper must use.
     */
    @Nested
    inner class StaleNodeRetention {

        @Test
        fun `the default window dwarfs the reconciliation grace period`() {
            val properties = ClusterProperties()
            val gracePeriod = Duration.ofMillis(properties.scheduledTasks.reconciliationGracePeriodMs)

            assertThat(properties.effectiveStaleNodeRetention()).isEqualTo(Duration.ofDays(7))
            assertThat(properties.effectiveStaleNodeRetention()).isGreaterThan(gracePeriod.multipliedBy(24))
        }

        @Test
        fun `a window shorter than the grace period is clamped up to the floor`() {
            val properties = ClusterProperties(
                nodeReaper = ClusterNodeReaperProperties(staleNodeRetention = Duration.ofMinutes(1)),
            )

            // 4 x the 15-minute default grace period.
            assertThat(properties.effectiveStaleNodeRetention()).isEqualTo(Duration.ofMinutes(60))
        }

        @Test
        fun `a window longer than the floor is used as configured`() {
            val properties = ClusterProperties(
                nodeReaper = ClusterNodeReaperProperties(staleNodeRetention = Duration.ofDays(30)),
            )

            assertThat(properties.effectiveStaleNodeRetention()).isEqualTo(Duration.ofDays(30))
        }

        @Test
        fun `the floor tracks a shortened reconciliation grace period`() {
            val properties = ClusterProperties(
                scheduledTasks = ClusterScheduledTaskProperties(reconciliationGracePeriodMs = 60_000),
                nodeReaper = ClusterNodeReaperProperties(staleNodeRetention = Duration.ofSeconds(1)),
            )

            assertThat(properties.effectiveStaleNodeRetention()).isEqualTo(Duration.ofMinutes(4))
        }

        @Test
        fun `the default reaper cron does not collide with the other nightly maintenance tasks`() {
            // 02:00 partitions, 03:00 stale consumer nodes, 03:30 application-log retention.
            assertThat(ClusterNodeReaperProperties().cron).isEqualTo("0 15 4 * * *")
        }
    }
}
