// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.upgrade

import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * After an upgrade from 1.x nothing generates until each catalog has its first 2.0 release, so the
 * upgrade's first pass runs as soon as the installation starts, not one interval later.
 */
class CatalogUpgradeScheduleTest : IntegrationTestBase() {

    @Autowired private lateinit var definitions: List<ClusterScheduledTaskDefinition>

    @Test
    fun `the first upgrade pass is due at startup`() {
        val schedule = definitions.single { it.taskKey == CatalogUpgradeRunner.TASK_KEY }.schedule
        assertThat(schedule).isInstanceOf(ClusterScheduledTaskSchedule.FixedDelay::class.java)
        assertThat((schedule as ClusterScheduledTaskSchedule.FixedDelay).initialDelayMs).isZero()
    }
}
