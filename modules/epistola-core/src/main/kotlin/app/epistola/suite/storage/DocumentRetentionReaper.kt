// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.storage

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.documents.cleanup.PartitionProperties
import app.epistola.suite.observability.recordScheduledTask
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component

/**
 * Drives backend-specific retention of generated documents (issue #738): a single-owner, daily
 * cluster task that calls every [ContentRetentionMaintainer]. Only the filesystem document backend
 * contributes one (its age sweep); PostgreSQL reclaims by partition drops and S3 by its lifecycle
 * rule.
 *
 * Asset bytes are not this task's: the content collector (`ResourceContentCollector`, ADR 0033)
 * deletes the bytes nothing holds, with the catalog content they belonged to, and gauges what is
 * left. This task used to sweep them too, with its own grace period and gauge.
 */
@Component
@ConditionalOnProperty(
    name = ["epistola.storage.reaper.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class DocumentRetentionReaper(
    private val meterRegistry: MeterRegistry,
    private val maintainers: List<ContentRetentionMaintainer>,
    private val partitions: PartitionProperties,
    @Value("\${epistola.storage.reaper.cron:0 30 3 * * ?}")
    private val reaperCron: String,
) : ClusterScheduledTaskHandler {
    private val logger = LoggerFactory.getLogger(javaClass)
    override val taskType: String = TASK_TYPE

    @Bean
    fun documentRetentionScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.Cron(reaperCron),
        executionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
    )

    override fun handle(task: ClusterScheduledTask) {
        reap()
    }

    fun reap() {
        meterRegistry.recordScheduledTask("document-retention") {
            maintainers.forEach { maintainer ->
                try {
                    maintainer.reclaim(partitions.retentionMonths)
                } catch (e: Exception) {
                    logger.error("Content retention maintainer {} failed: {}", maintainer.javaClass.simpleName, e.message, e)
                }
            }
        }
    }

    companion object {
        const val TASK_KEY = "core.document-retention"
        const val ROUTING_KEY = "system:core.document-retention"
        const val TASK_TYPE = "core.document-retention"
    }
}
