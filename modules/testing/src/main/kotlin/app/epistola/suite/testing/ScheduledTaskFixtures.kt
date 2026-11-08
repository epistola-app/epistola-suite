// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskScheduleKind
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * A due occurrence of [definition], as the cluster scheduler hands it to its handler: for tests
 * that call `handle(task)` directly, without claiming the row.
 */
fun dueScheduledTask(definition: ClusterScheduledTaskDefinition, now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)): ClusterScheduledTask = ClusterScheduledTask(
    taskKey = definition.taskKey,
    tenantKey = definition.tenantKey,
    routingKey = definition.routingKey,
    taskType = definition.taskType,
    executionScope = definition.executionScope,
    requiredCapability = definition.requiredCapability,
    payload = definition.payload,
    scheduleKind = when (definition.schedule) {
        is ClusterScheduledTaskSchedule.Cron -> ClusterScheduledTaskScheduleKind.CRON
        is ClusterScheduledTaskSchedule.FixedDelay -> ClusterScheduledTaskScheduleKind.FIXED_DELAY
        is ClusterScheduledTaskSchedule.FixedRate -> ClusterScheduledTaskScheduleKind.FIXED_RATE
    },
    cronExpression = (definition.schedule as? ClusterScheduledTaskSchedule.Cron)?.expression,
    intervalMs = (definition.schedule as? ClusterScheduledTaskSchedule.FixedDelay)?.intervalMs ?: (definition.schedule as? ClusterScheduledTaskSchedule.FixedRate)?.intervalMs,
    zoneId = definition.zoneId,
    failurePolicy = definition.failurePolicy,
    catchUpPolicy = definition.catchUpPolicy,
    enabled = definition.enabled,
    nextDueAt = now,
    leaseOwnerNodeId = null,
    leaseExpiresAt = null,
    lastStartedAt = null,
    lastCompletedAt = null,
    lastFailedAt = null,
    attemptCount = 0,
    consecutiveFailures = 0,
    lastError = null,
    createdAt = now,
    updatedAt = now,
)
