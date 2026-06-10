package app.epistola.suite.cluster.schedules

import app.epistola.suite.cluster.ClusterProperties
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.observability.NodeIdentity
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.OffsetDateTime

@Component
class ClusterScheduledTaskRegistry(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val nodeIdentity: NodeIdentity,
    private val properties: ClusterProperties,
    private val scheduleCalculator: ClusterScheduledTaskScheduleCalculator,
    private val clock: Clock,
) {
    private val payloadType = objectMapper.typeFactory.constructMapType(Map::class.java, String::class.java, Any::class.java)

    fun upsert(definition: ClusterScheduledTaskDefinition): ClusterScheduledTask {
        val payloadJson = objectMapper.writeValueAsString(definition.payload)
        val scheduleFields = scheduleFields(definition.schedule)
        val initialDueAt = scheduleCalculator.initialDueAt(definition)
        val now = OffsetDateTime.now(clock)
        return jdbi.withHandle<ClusterScheduledTask, Exception> { handle ->
            handle.createQuery(
                """
                INSERT INTO cluster_tasks_scheduled (
                    task_key, tenant_key, routing_key, task_type, required_capability, payload, schedule_kind,
                    cron_expression, interval_ms, zone_id, failure_policy, catch_up_policy,
                    enabled, next_due_at, updated_at
                )
                VALUES (
                    :taskKey, :tenantKey, :routingKey, :taskType, :requiredCapability, :payload::jsonb, :scheduleKind,
                    :cronExpression, :intervalMs, :zoneId, :failurePolicy, :catchUpPolicy,
                    :enabled, :initialDueAt, :now
                )
                ON CONFLICT (task_key) DO UPDATE
                SET tenant_key = EXCLUDED.tenant_key,
                    routing_key = EXCLUDED.routing_key,
                    task_type = EXCLUDED.task_type,
                    required_capability = EXCLUDED.required_capability,
                    payload = EXCLUDED.payload,
                    next_due_at = CASE
                        WHEN cluster_tasks_scheduled.schedule_kind <> EXCLUDED.schedule_kind
                          OR cluster_tasks_scheduled.cron_expression IS DISTINCT FROM EXCLUDED.cron_expression
                          OR cluster_tasks_scheduled.interval_ms IS DISTINCT FROM EXCLUDED.interval_ms
                          OR cluster_tasks_scheduled.zone_id <> EXCLUDED.zone_id
                        THEN EXCLUDED.next_due_at
                        ELSE cluster_tasks_scheduled.next_due_at
                    END,
                    schedule_kind = EXCLUDED.schedule_kind,
                    cron_expression = EXCLUDED.cron_expression,
                    interval_ms = EXCLUDED.interval_ms,
                    zone_id = EXCLUDED.zone_id,
                    failure_policy = EXCLUDED.failure_policy,
                    catch_up_policy = EXCLUDED.catch_up_policy,
                    enabled = EXCLUDED.enabled,
                    updated_at = :now
                RETURNING ${selectColumns()}
                """,
            )
                .bind("taskKey", definition.taskKey)
                .bind("tenantKey", definition.tenantKey?.value)
                .bind("routingKey", definition.routingKey)
                .bind("taskType", definition.taskType)
                .bind("requiredCapability", normalizedCapability(definition.requiredCapability))
                .bind("payload", payloadJson)
                .bind("scheduleKind", scheduleFields.kind.dbValue)
                .bind("cronExpression", scheduleFields.cronExpression)
                .bind("intervalMs", scheduleFields.intervalMs)
                .bind("zoneId", definition.zoneId)
                .bind("failurePolicy", definition.failurePolicy.dbValue)
                .bind("catchUpPolicy", definition.catchUpPolicy.dbValue)
                .bind("enabled", definition.enabled)
                .bind("initialDueAt", initialDueAt)
                .bind("now", now)
                .map { rs, _ -> mapTask(rs) }
                .one()
        }
    }

    fun dueCandidates(limit: Int = properties.scheduledTasks.candidateScanSize): List<ClusterScheduledTask> = jdbi.withHandle<List<ClusterScheduledTask>, Exception> { handle ->
        val now = OffsetDateTime.now(clock)
        handle.createQuery(
            """
            SELECT ${selectColumns()}
            FROM cluster_tasks_scheduled
            WHERE enabled = true
              AND next_due_at <= :now
              AND (lease_owner_node_id IS NULL OR lease_expires_at < :now)
            ORDER BY next_due_at, task_key
            LIMIT :limit
            """,
        )
            .bind("limit", limit)
            .bind("now", now)
            .map { rs, _ -> mapTask(rs) }
            .list()
    }

    fun claimDue(taskKeys: Collection<String>, batchSize: Int = properties.scheduledTasks.batchSize): List<ClusterScheduledTask> {
        if (taskKeys.isEmpty()) return emptyList()
        val now = OffsetDateTime.now(clock)
        val activeSince = now.minusNanos(properties.idleTimeoutMs * NANOS_PER_MILLI)
        val leaseExpiresAt = now.plusNanos(properties.scheduledTasks.leaseDurationMs * NANOS_PER_MILLI)
        return jdbi.inTransaction<List<ClusterScheduledTask>, Exception> { handle ->
            handle.createQuery(
                """
                WITH due AS (
                    SELECT task_key
                    FROM cluster_tasks_scheduled
                    WHERE task_key IN (<taskKeys>)
                      AND enabled = true
                      AND next_due_at <= :now
                      AND (lease_owner_node_id IS NULL OR lease_expires_at < :now)
                      AND EXISTS (
                          SELECT 1
                          FROM cluster_nodes n
                          WHERE n.node_id = :nodeId
                            AND n.last_seen_at > :activeSince
                            AND jsonb_exists(n.capabilities, cluster_tasks_scheduled.required_capability)
                      )
                    ORDER BY next_due_at, task_key
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE cluster_tasks_scheduled t
                SET lease_owner_node_id = :nodeId,
                    lease_expires_at = :leaseExpiresAt,
                    last_started_at = :now,
                    attempt_count = attempt_count + 1,
                    updated_at = :now
                FROM due
                WHERE t.task_key = due.task_key
                RETURNING ${selectColumns("t")}
                """,
            )
                .bindList("taskKeys", taskKeys)
                .bind("batchSize", batchSize)
                .bind("nodeId", nodeIdentity.nodeId)
                .bind("activeSince", activeSince)
                .bind("now", now)
                .bind("leaseExpiresAt", leaseExpiresAt)
                .map { rs, _ -> mapTask(rs) }
                .list()
        }
    }

    fun complete(taskKey: String, nextDueAt: OffsetDateTime): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        val now = OffsetDateTime.now(clock)
        handle.createUpdate(
            """
            UPDATE cluster_tasks_scheduled
            SET next_due_at = :nextDueAt,
                lease_owner_node_id = NULL,
                lease_expires_at = NULL,
                last_completed_at = :now,
                consecutive_failures = 0,
                last_error = NULL,
                updated_at = :now
            WHERE task_key = :taskKey
              AND lease_owner_node_id = :nodeId
            """,
        )
            .bind("taskKey", taskKey)
            .bind("nodeId", nodeIdentity.nodeId)
            .bind("nextDueAt", nextDueAt)
            .bind("now", now)
            .execute() == 1
    }

    fun fail(taskKey: String, nextDueAt: OffsetDateTime, error: String): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        val now = OffsetDateTime.now(clock)
        handle.createUpdate(
            """
            UPDATE cluster_tasks_scheduled
            SET next_due_at = :nextDueAt,
                lease_owner_node_id = NULL,
                lease_expires_at = NULL,
                last_failed_at = :now,
                consecutive_failures = consecutive_failures + 1,
                last_error = :error,
                updated_at = :now
            WHERE task_key = :taskKey
              AND lease_owner_node_id = :nodeId
            """,
        )
            .bind("taskKey", taskKey)
            .bind("nodeId", nodeIdentity.nodeId)
            .bind("nextDueAt", nextDueAt)
            .bind("error", error.take(MAX_ERROR_LENGTH))
            .bind("now", now)
            .execute() == 1
    }

    fun enable(taskKey: String, tenantKey: TenantKey? = null): Boolean = setEnabled(taskKey, tenantKey, true)

    fun disable(taskKey: String, tenantKey: TenantKey? = null): Boolean = setEnabled(taskKey, tenantKey, false)

    fun triggerNow(taskKey: String, tenantKey: TenantKey? = null): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        val now = OffsetDateTime.now(clock)
        handle.createUpdate(
            """
            UPDATE cluster_tasks_scheduled
            SET next_due_at = :now,
                lease_owner_node_id = NULL,
                lease_expires_at = NULL,
                updated_at = :now
            WHERE task_key = :taskKey
              AND tenant_key IS NOT DISTINCT FROM :tenantKey
            """,
        )
            .bind("taskKey", taskKey)
            .bind("tenantKey", tenantKey?.value)
            .bind("now", now)
            .execute() == 1
    }

    fun find(taskKey: String): ClusterScheduledTask? = jdbi.withHandle<ClusterScheduledTask?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT ${selectColumns()}
            FROM cluster_tasks_scheduled
            WHERE task_key = :taskKey
            """,
        )
            .bind("taskKey", taskKey)
            .map { rs, _ -> mapTask(rs) }
            .findOne()
            .orElse(null)
    }

    fun list(): List<ClusterScheduledTask> = jdbi.withHandle<List<ClusterScheduledTask>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT ${selectColumns()}
            FROM cluster_tasks_scheduled
            ORDER BY task_key
            """,
        )
            .map { rs, _ -> mapTask(rs) }
            .list()
    }

    private fun setEnabled(taskKey: String, tenantKey: TenantKey?, enabled: Boolean): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        val now = OffsetDateTime.now(clock)
        handle.createUpdate(
            """
            UPDATE cluster_tasks_scheduled
            SET enabled = :enabled,
                lease_owner_node_id = CASE WHEN :enabled THEN lease_owner_node_id ELSE NULL END,
                lease_expires_at = CASE WHEN :enabled THEN lease_expires_at ELSE NULL END,
                updated_at = :now
            WHERE task_key = :taskKey
              AND tenant_key IS NOT DISTINCT FROM :tenantKey
            """,
        )
            .bind("taskKey", taskKey)
            .bind("tenantKey", tenantKey?.value)
            .bind("enabled", enabled)
            .bind("now", now)
            .execute() == 1
    }

    private fun scheduleFields(schedule: ClusterScheduledTaskSchedule): ScheduleFields = when (schedule) {
        is ClusterScheduledTaskSchedule.Cron -> ScheduleFields(ClusterScheduledTaskScheduleKind.CRON, schedule.expression, null)
        is ClusterScheduledTaskSchedule.FixedDelay -> ScheduleFields(ClusterScheduledTaskScheduleKind.FIXED_DELAY, null, schedule.intervalMs)
        is ClusterScheduledTaskSchedule.FixedRate -> ScheduleFields(ClusterScheduledTaskScheduleKind.FIXED_RATE, null, schedule.intervalMs)
    }

    private fun mapTask(rs: java.sql.ResultSet): ClusterScheduledTask = ClusterScheduledTask(
        taskKey = rs.getString("task_key"),
        tenantKey = rs.getString("tenant_key")?.let { TenantKey.of(it) },
        routingKey = rs.getString("routing_key"),
        taskType = rs.getString("task_type"),
        requiredCapability = rs.getString("required_capability"),
        payload = readPayload(rs.getString("payload")),
        scheduleKind = ClusterScheduledTaskScheduleKind.fromDb(rs.getString("schedule_kind")),
        cronExpression = rs.getString("cron_expression"),
        intervalMs = rs.getLong("interval_ms").takeUnless { rs.wasNull() },
        zoneId = rs.getString("zone_id"),
        failurePolicy = ClusterScheduledTaskFailurePolicy.fromDb(rs.getString("failure_policy")),
        catchUpPolicy = ClusterScheduledTaskCatchUpPolicy.fromDb(rs.getString("catch_up_policy")),
        enabled = rs.getBoolean("enabled"),
        nextDueAt = rs.getObject("next_due_at", OffsetDateTime::class.java),
        leaseOwnerNodeId = rs.getString("lease_owner_node_id"),
        leaseExpiresAt = rs.getObject("lease_expires_at", OffsetDateTime::class.java),
        lastStartedAt = rs.getObject("last_started_at", OffsetDateTime::class.java),
        lastCompletedAt = rs.getObject("last_completed_at", OffsetDateTime::class.java),
        lastFailedAt = rs.getObject("last_failed_at", OffsetDateTime::class.java),
        attemptCount = rs.getInt("attempt_count"),
        consecutiveFailures = rs.getInt("consecutive_failures"),
        lastError = rs.getString("last_error"),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
        updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java),
    )

    private fun readPayload(json: String?): Map<String, Any?> = runCatching {
        objectMapper.readValue<Map<String, Any?>>(json ?: "{}", payloadType)
    }.getOrDefault(emptyMap())

    private fun selectColumns(prefix: String? = null): String {
        val p = prefix?.let { "$it." } ?: ""
        return """
            ${p}task_key,
            ${p}tenant_key,
            ${p}routing_key,
            ${p}task_type,
            ${p}required_capability,
            ${p}payload::text AS payload,
            ${p}schedule_kind,
            ${p}cron_expression,
            ${p}interval_ms,
            ${p}zone_id,
            ${p}failure_policy,
            ${p}catch_up_policy,
            ${p}enabled,
            ${p}next_due_at,
            ${p}lease_owner_node_id,
            ${p}lease_expires_at,
            ${p}last_started_at,
            ${p}last_completed_at,
            ${p}last_failed_at,
            ${p}attempt_count,
            ${p}consecutive_failures,
            ${p}last_error,
            ${p}created_at,
            ${p}updated_at
        """.trimIndent()
    }

    private data class ScheduleFields(
        val kind: ClusterScheduledTaskScheduleKind,
        val cronExpression: String?,
        val intervalMs: Long?,
    )

    private fun normalizedCapability(requiredCapability: String): String = requiredCapability
        .trim()
        .ifEmpty { ClusterProperties.DEFAULT_CAPABILITY }

    private companion object {
        const val MAX_ERROR_LENGTH = 2_000
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
