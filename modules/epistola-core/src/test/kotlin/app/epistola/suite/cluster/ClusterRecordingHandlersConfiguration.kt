// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.cluster

import app.epistola.suite.cluster.schedules.RecordingClusterScheduledTaskHandler
import app.epistola.suite.cluster.timers.RecordingClusterTimerHandler
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * The recording timer and scheduled-task handlers the scheduler tests observe. One configuration
 * for both so those tests share a single Spring context instead of booting one each.
 */
@TestConfiguration(proxyBeanMethods = false)
class ClusterRecordingHandlersConfiguration {
    @Bean
    fun recordingClusterTimerHandler(): RecordingClusterTimerHandler = RecordingClusterTimerHandler()

    @Bean
    fun recordingClusterScheduledTaskHandler(): RecordingClusterScheduledTaskHandler = RecordingClusterScheduledTaskHandler()
}
