package app.epistola.suite.cluster.schedules

import app.epistola.suite.cluster.ClusterProperties
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * Internal persistence boundary for recurring scheduled task definitions and
 * runtime state.
 *
 * Normal callers should use the mediator-facing scheduled-task commands and
 * queries. The scheduler uses this registry directly for lease transitions:
 * claiming due work, completing a run, recording failure, and releasing leases.
 * Keeping those operations together avoids scattering the row-state machine and
 * `FOR UPDATE SKIP LOCKED` claim semantics across unrelated command handlers.
 */
@Component
class ClusterScheduledTaskRegistry(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val nodeIdentity: NodeIdentity,
    private val properties: ClusterProperties,
    private val scheduleCalculator: ClusterScheduledTaskScheduleCalculator,
) {
    private val payloadType = objectMapper.typeFactory.constructMapType(Map::class.java, String::class.java, Any::class.java)

    fun upsert(definition: ClusterScheduledTaskDefinition): ClusterScheduledTask = jdbi.inTransaction<ClusterScheduledTask, Exception> { handle ->
        upsertInTx(handle, definition)
    }

    /**
     * Atomically (re-)registers every code definition for this node: upserts each
     * row and records this node's vouch in one transaction. Reclaiming a retired
     * task and recording the vouch in the same commit closes the window where a
     * concurrent reconcile could re-retire a task that was just brought back but
     * not yet vouched for.
     */
    fun registerAll(definitions: List<ClusterScheduledTaskDefinition>) {
        jdbi.useTransaction<Exception> { handle ->
            definitions.forEach { upsertInTx(handle, it) }
            recordNodeRegistrationsInTx(handle, definitions.map { it.taskKey })
        }
    }

    private fun upsertInTx(handle: Handle, definition: ClusterScheduledTaskDefinition): ClusterScheduledTask {
        val payloadJson = objectMapper.writeValueAsString(definition.payload)
        val scheduleFields = scheduleFields(definition.schedule)
        val initialDueAt = scheduleCalculator.initialDueAt(definition)
        val now = EpistolaClock.offsetDateTime()
        val task = handle.createQuery(
            """
                INSERT INTO cluster_tasks_scheduled (
                    task_key, tenant_key, routing_key, task_type, execution_scope, required_capability, payload,
                    schedule_kind, cron_expression, interval_ms, zone_id, failure_policy, catch_up_policy,
                    enabled, next_due_at, updated_at
                )
                VALUES (
                    :taskKey, :tenantKey, :routingKey, :taskType, :executionScope, :requiredCapability,
                    :payload::jsonb, :scheduleKind, :cronExpression, :intervalMs, :zoneId, :failurePolicy,
                    :catchUpPolicy, :enabled, :initialDueAt, :now
                )
                ON CONFLICT (task_key) DO UPDATE
                SET tenant_key = EXCLUDED.tenant_key,
                    routing_key = EXCLUDED.routing_key,
                    task_type = EXCLUDED.task_type,
                    execution_scope = EXCLUDED.execution_scope,
                    required_capability = EXCLUDED.required_capability,
                    payload = EXCLUDED.payload,
                    next_due_at = CASE
                        WHEN cluster_tasks_scheduled.execution_scope <> EXCLUDED.execution_scope
                          OR cluster_tasks_scheduled.schedule_kind <> EXCLUDED.schedule_kind
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
            .bind("executionScope", definition.executionScope.dbValue)
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
        // A single-owner task must never carry per-node runtime rows; clear any
        // left over from a prior EACH_CAPABLE_NODE incarnation of this key.
        if (task.executionScope == ClusterScheduledTaskExecutionScope.SINGLE_OWNER) {
            deleteNodeState(handle, task.taskKey)
        }
        return task
    }

    fun dueCandidates(limit: Int = properties.scheduledTasks.candidateScanSize): List<ClusterScheduledTask> = jdbi.withHandle<List<ClusterScheduledTask>, Exception> { handle ->
        val now = EpistolaClock.offsetDateTime()
        val runDeadline = now.minusNanos(properties.scheduledTasks.maxRunDurationMs * NANOS_PER_MILLI)
        val singletonCandidates = handle.createQuery(
            """
            SELECT ${selectColumns()}
            FROM cluster_tasks_scheduled
            WHERE enabled = true
              AND execution_scope = :singleOwner
              AND next_due_at <= :now
              -- Normally only an absent/expired lease is a candidate; the last clause
              -- adds the hard-deadline escape so a task wedged in-flight past
              -- maxRunDurationMs (lease renewed forever by a wedged node) is offered
              -- for force-reclaim (#723).
              AND (lease_owner_node_id IS NULL OR lease_expires_at < :now OR last_started_at < :runDeadline)
            ORDER BY next_due_at, task_key
            LIMIT :limit
            """,
        )
            .bind("singleOwner", ClusterScheduledTaskExecutionScope.SINGLE_OWNER.dbValue)
            .bind("limit", limit)
            .bind("now", now)
            .bind("runDeadline", runDeadline)
            .map { rs, _ -> mapTask(rs) }
            .list()

        val perNodeLimit = (limit - singletonCandidates.size).coerceAtLeast(0)
        if (perNodeLimit == 0) {
            return@withHandle singletonCandidates
        }

        val activeSince = now.minusNanos(properties.idleTimeoutMs * NANOS_PER_MILLI)
        val perNodeCandidates = handle.createQuery(
            """
            SELECT ${selectPerNodeColumns()}
            FROM cluster_tasks_scheduled t
            LEFT JOIN cluster_tasks_scheduled_node_state s
              ON s.task_key = t.task_key AND s.node_id = :nodeId
            WHERE t.enabled = true
              AND t.execution_scope = :eachCapableNode
              AND COALESCE(s.next_due_at, t.next_due_at) <= :now
              AND (s.lease_expires_at IS NULL OR s.lease_expires_at < :now)
              AND EXISTS (
                  SELECT 1
                  FROM cluster_nodes n
                  WHERE n.node_id = :nodeId
                    AND n.last_seen_at > :activeSince
                    AND jsonb_exists(n.capabilities, t.required_capability)
              )
            ORDER BY COALESCE(s.next_due_at, t.next_due_at), t.task_key
            LIMIT :limit
            """,
        )
            .bind("eachCapableNode", ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE.dbValue)
            .bind("nodeId", nodeIdentity.nodeId)
            .bind("activeSince", activeSince)
            .bind("limit", perNodeLimit)
            .bind("now", now)
            .map { rs, _ -> mapTask(rs) }
            .list()

        singletonCandidates + perNodeCandidates
    }

    fun claimDue(taskKeys: Collection<String>, batchSize: Int = properties.scheduledTasks.batchSize): List<ClusterScheduledTask> {
        if (taskKeys.isEmpty()) return emptyList()
        val now = EpistolaClock.offsetDateTime()
        val activeSince = now.minusNanos(properties.idleTimeoutMs * NANOS_PER_MILLI)
        val runDeadline = now.minusNanos(properties.scheduledTasks.maxRunDurationMs * NANOS_PER_MILLI)
        val leaseExpiresAt = now.plusNanos(properties.scheduledTasks.leaseDurationMs * NANOS_PER_MILLI)
        return jdbi.inTransaction<List<ClusterScheduledTask>, Exception> { handle ->
            val singleOwnerClaimed = handle.createQuery(
                """
                WITH due AS (
                    SELECT task_key
                    FROM cluster_tasks_scheduled
                    WHERE task_key IN (<taskKeys>)
                      AND enabled = true
                      AND execution_scope = :singleOwner
                      AND next_due_at <= :now
                      -- Absent/expired lease, OR force-reclaim of a run wedged in-flight
                      -- past maxRunDurationMs whose lease is being renewed forever (#723).
                      AND (lease_owner_node_id IS NULL OR lease_expires_at < :now OR last_started_at < :runDeadline)
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
                .bind("singleOwner", ClusterScheduledTaskExecutionScope.SINGLE_OWNER.dbValue)
                .bind("batchSize", batchSize)
                .bind("nodeId", nodeIdentity.nodeId)
                .bind("activeSince", activeSince)
                .bind("now", now)
                .bind("runDeadline", runDeadline)
                .bind("leaseExpiresAt", leaseExpiresAt)
                .map { rs, _ -> mapTask(rs) }
                .list()

            val remainingBatchSize = (batchSize - singleOwnerClaimed.size).coerceAtLeast(0)
            if (remainingBatchSize == 0) {
                return@inTransaction singleOwnerClaimed
            }

            handle.createUpdate(
                """
                INSERT INTO cluster_tasks_scheduled_node_state (task_key, node_id, next_due_at, updated_at)
                SELECT t.task_key, :nodeId, t.next_due_at, :now
                FROM cluster_tasks_scheduled t
                WHERE t.task_key IN (<taskKeys>)
                  AND t.enabled = true
                  AND t.execution_scope = :eachCapableNode
                  AND EXISTS (
                      SELECT 1
                      FROM cluster_nodes n
                      WHERE n.node_id = :nodeId
                        AND n.last_seen_at > :activeSince
                        AND jsonb_exists(n.capabilities, t.required_capability)
                  )
                ON CONFLICT (task_key, node_id) DO NOTHING
                """,
            )
                .bindList("taskKeys", taskKeys)
                .bind("eachCapableNode", ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE.dbValue)
                .bind("nodeId", nodeIdentity.nodeId)
                .bind("activeSince", activeSince)
                .bind("now", now)
                .execute()

            val perNodeClaimed = handle.createQuery(
                """
                WITH due AS (
                    SELECT s.task_key, s.node_id
                    FROM cluster_tasks_scheduled_node_state s
                    JOIN cluster_tasks_scheduled t ON t.task_key = s.task_key
                    WHERE s.task_key IN (<taskKeys>)
                      AND s.node_id = :nodeId
                      AND t.enabled = true
                      AND t.execution_scope = :eachCapableNode
                      AND s.next_due_at <= :now
                      AND (s.lease_expires_at IS NULL OR s.lease_expires_at < :now)
                      AND EXISTS (
                          SELECT 1
                          FROM cluster_nodes n
                          WHERE n.node_id = :nodeId
                            AND n.last_seen_at > :activeSince
                            AND jsonb_exists(n.capabilities, t.required_capability)
                      )
                    ORDER BY s.next_due_at, s.task_key
                    LIMIT :batchSize
                    FOR UPDATE OF s SKIP LOCKED
                )
                UPDATE cluster_tasks_scheduled_node_state s
                SET lease_expires_at = :leaseExpiresAt,
                    last_started_at = :now,
                    attempt_count = attempt_count + 1,
                    updated_at = :now
                FROM due
                WHERE s.task_key = due.task_key
                  AND s.node_id = due.node_id
                RETURNING s.task_key
                """,
            )
                .bindList("taskKeys", taskKeys)
                .bind("eachCapableNode", ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE.dbValue)
                .bind("batchSize", remainingBatchSize)
                .bind("nodeId", nodeIdentity.nodeId)
                .bind("activeSince", activeSince)
                .bind("now", now)
                .bind("leaseExpiresAt", leaseExpiresAt)
                .mapTo(String::class.java)
                .list()

            singleOwnerClaimed + findPerNode(handle, perNodeClaimed)
        }
    }

    /**
     * Extends the lease on tasks this node is currently executing so a handler
     * that runs longer than [ClusterScheduledTaskProperties.leaseDurationMs] is
     * not reclaimed by another node. Only rows still leased to this node are
     * touched, so a row reclaimed after a genuine stall is left alone. Returns the
     * number of leases renewed (single-owner + per-node).
     */
    fun renewLeases(taskKeys: Collection<String>): Int {
        if (taskKeys.isEmpty()) return 0
        val now = EpistolaClock.offsetDateTime()
        val leaseExpiresAt = now.plusNanos(properties.scheduledTasks.leaseDurationMs * NANOS_PER_MILLI)
        return jdbi.withHandle<Int, Exception> { handle ->
            val singleOwner = handle.createUpdate(
                """
                UPDATE cluster_tasks_scheduled
                SET lease_expires_at = :leaseExpiresAt,
                    updated_at = :now
                WHERE task_key IN (<taskKeys>)
                  AND execution_scope = :singleOwner
                  AND lease_owner_node_id = :nodeId
                """,
            )
                .setQueryTimeout(LIVENESS_QUERY_TIMEOUT_SECONDS)
                .bindList("taskKeys", taskKeys)
                .bind("singleOwner", ClusterScheduledTaskExecutionScope.SINGLE_OWNER.dbValue)
                .bind("nodeId", nodeIdentity.nodeId)
                .bind("leaseExpiresAt", leaseExpiresAt)
                .bind("now", now)
                .execute()

            val perNode = handle.createUpdate(
                """
                UPDATE cluster_tasks_scheduled_node_state
                SET lease_expires_at = :leaseExpiresAt,
                    updated_at = :now
                WHERE task_key IN (<taskKeys>)
                  AND node_id = :nodeId
                  AND lease_expires_at IS NOT NULL
                """,
            )
                .setQueryTimeout(LIVENESS_QUERY_TIMEOUT_SECONDS)
                .bindList("taskKeys", taskKeys)
                .bind("nodeId", nodeIdentity.nodeId)
                .bind("leaseExpiresAt", leaseExpiresAt)
                .bind("now", now)
                .execute()

            singleOwner + perNode
        }
    }

    fun complete(taskKey: String, nextDueAt: OffsetDateTime): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        val now = EpistolaClock.offsetDateTime()
        val singletonCompleted = handle.createUpdate(
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
              AND execution_scope = :singleOwner
              AND lease_owner_node_id = :nodeId
            """,
        )
            .bind("taskKey", taskKey)
            .bind("singleOwner", ClusterScheduledTaskExecutionScope.SINGLE_OWNER.dbValue)
            .bind("nodeId", nodeIdentity.nodeId)
            .bind("nextDueAt", nextDueAt)
            .bind("now", now)
            .execute() == 1
        if (singletonCompleted) return@withHandle true

        handle.createUpdate(
            """
            UPDATE cluster_tasks_scheduled_node_state s
            SET next_due_at = :nextDueAt,
                lease_expires_at = NULL,
                last_completed_at = :now,
                consecutive_failures = 0,
                last_error = NULL,
                updated_at = :now
            FROM cluster_tasks_scheduled t
            WHERE s.task_key = t.task_key
              AND s.task_key = :taskKey
              AND s.node_id = :nodeId
              AND t.execution_scope = :eachCapableNode
              AND s.lease_expires_at IS NOT NULL
            """,
        )
            .bind("taskKey", taskKey)
            .bind("eachCapableNode", ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE.dbValue)
            .bind("nodeId", nodeIdentity.nodeId)
            .bind("nextDueAt", nextDueAt)
            .bind("now", now)
            .execute() == 1
    }

    fun fail(taskKey: String, nextDueAt: OffsetDateTime, error: String): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        val now = EpistolaClock.offsetDateTime()
        val singletonFailed = handle.createUpdate(
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
              AND execution_scope = :singleOwner
              AND lease_owner_node_id = :nodeId
            """,
        )
            .bind("taskKey", taskKey)
            .bind("singleOwner", ClusterScheduledTaskExecutionScope.SINGLE_OWNER.dbValue)
            .bind("nodeId", nodeIdentity.nodeId)
            .bind("nextDueAt", nextDueAt)
            .bind("error", error.take(MAX_ERROR_LENGTH))
            .bind("now", now)
            .execute() == 1
        if (singletonFailed) return@withHandle true

        handle.createUpdate(
            """
            UPDATE cluster_tasks_scheduled_node_state s
            SET next_due_at = :nextDueAt,
                lease_expires_at = NULL,
                last_failed_at = :now,
                consecutive_failures = s.consecutive_failures + 1,
                last_error = :error,
                updated_at = :now
            FROM cluster_tasks_scheduled t
            WHERE s.task_key = t.task_key
              AND s.task_key = :taskKey
              AND s.node_id = :nodeId
              AND t.execution_scope = :eachCapableNode
              AND s.lease_expires_at IS NOT NULL
            """,
        )
            .bind("taskKey", taskKey)
            .bind("eachCapableNode", ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE.dbValue)
            .bind("nodeId", nodeIdentity.nodeId)
            .bind("nextDueAt", nextDueAt)
            .bind("error", error.take(MAX_ERROR_LENGTH))
            .bind("now", now)
            .execute() == 1
    }

    fun enable(taskKey: String, tenantKey: TenantKey? = null): Boolean = setEnabled(taskKey, tenantKey, true)

    fun disable(taskKey: String, tenantKey: TenantKey? = null): Boolean = setEnabled(taskKey, tenantKey, false)

    fun triggerNow(taskKey: String, tenantKey: TenantKey? = null): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        val now = EpistolaClock.offsetDateTime()
        val updated = handle.createUpdate(
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

        if (updated) {
            handle.createUpdate(
                """
                UPDATE cluster_tasks_scheduled_node_state s
                SET next_due_at = :now,
                    lease_expires_at = NULL,
                    updated_at = :now
                FROM cluster_tasks_scheduled t
                WHERE s.task_key = t.task_key
                  AND s.task_key = :taskKey
                  AND t.tenant_key IS NOT DISTINCT FROM :tenantKey
                """,
            )
                .bind("taskKey", taskKey)
                .bind("tenantKey", tenantKey?.value)
                .bind("now", now)
                .execute()
        }
        updated
    }

    /**
     * Records that this node carries [taskKeys] (one row per task) and prunes this
     * node's registration rows for any task it no longer carries. The combined set
     * of registrations (joined to live `cluster_nodes`) is what the reconciler uses
     * to decide whether a task is orphaned, so a node that restarts on a build
     * lacking a task must stop vouching for it.
     */
    fun recordNodeRegistrations(taskKeys: Collection<String>) {
        jdbi.useTransaction<Exception> { handle -> recordNodeRegistrationsInTx(handle, taskKeys) }
    }

    private fun recordNodeRegistrationsInTx(handle: Handle, taskKeys: Collection<String>) {
        val nodeId = nodeIdentity.nodeId
        if (taskKeys.isEmpty()) {
            handle.createUpdate("DELETE FROM cluster_scheduled_task_registrations WHERE node_id = :nodeId")
                .bind("nodeId", nodeId)
                .execute()
            return
        }

        handle.createUpdate(
            """
            DELETE FROM cluster_scheduled_task_registrations
            WHERE node_id = :nodeId
              AND task_key NOT IN (<taskKeys>)
            """,
        )
            .bindList("taskKeys", taskKeys)
            .bind("nodeId", nodeId)
            .execute()

        val batch = handle.prepareBatch(
            """
            INSERT INTO cluster_scheduled_task_registrations (task_key, node_id)
            VALUES (:taskKey, :nodeId)
            ON CONFLICT (task_key, node_id) DO NOTHING
            """,
        )
        taskKeys.forEach { taskKey ->
            batch
                .bind("taskKey", taskKey)
                .bind("nodeId", nodeId)
                .add()
        }
        batch.execute()
    }

    /**
     * Deletes every orphaned task — one **no node carrying it has been seen within
     * the grace window**, i.e. no registration row whose node has a
     * `cluster_nodes.last_seen_at` newer than [seenSince]. Per-node state and
     * registrations cascade. Returns the deleted task keys (for logging).
     *
     * Liveness comes from the heartbeat-maintained node row: a restarting node's
     * pre-restart `last_seen_at` keeps protecting its schedules until it has
     * genuinely been gone for the grace window; a registration whose node row is
     * gone or stale no longer protects. A task with no registrations at all is
     * orphaned — nothing carries it.
     */
    fun deleteOrphanedTasks(seenSince: OffsetDateTime): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        handle.createQuery(
            """
            DELETE FROM cluster_tasks_scheduled t
            WHERE NOT $LIVE_REGISTRATION_EXISTS
            RETURNING t.task_key
            """,
        )
            .bind("seenSince", seenSince)
            .mapTo(String::class.java)
            .list()
    }

    /**
     * Atomically deletes [taskKey] **iff** it is orphaned — no node carrying it has
     * been seen within the grace window ([seenSince]). Returns true if deleted.
     * Used by the scheduler to clean up an orphan the moment it fires with no
     * handler, instead of waiting for the periodic reconciler.
     */
    fun deleteIfOrphaned(taskKey: String, seenSince: OffsetDateTime): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createUpdate(
            """
            DELETE FROM cluster_tasks_scheduled t
            WHERE t.task_key = :taskKey
              AND NOT $LIVE_REGISTRATION_EXISTS
            """,
        )
            .bind("taskKey", taskKey)
            .bind("seenSince", seenSince)
            .execute() == 1
    }

    /**
     * Releases the lease and advances [taskKey] to [nextDueAt] without recording a
     * failure or error. Used when no handler is registered for a claimed task: the
     * task is almost certainly orphaned and awaiting retirement, so it should not
     * accrue failure/error state or be reclaimed in a tight loop.
     */
    fun skipNoHandler(taskKey: String, nextDueAt: OffsetDateTime): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        val now = EpistolaClock.offsetDateTime()
        val singletonSkipped = handle.createUpdate(
            """
            UPDATE cluster_tasks_scheduled
            SET next_due_at = :nextDueAt,
                lease_owner_node_id = NULL,
                lease_expires_at = NULL,
                updated_at = :now
            WHERE task_key = :taskKey
              AND execution_scope = :singleOwner
              AND lease_owner_node_id = :nodeId
            """,
        )
            .bind("taskKey", taskKey)
            .bind("singleOwner", ClusterScheduledTaskExecutionScope.SINGLE_OWNER.dbValue)
            .bind("nodeId", nodeIdentity.nodeId)
            .bind("nextDueAt", nextDueAt)
            .bind("now", now)
            .execute() == 1
        if (singletonSkipped) return@withHandle true

        handle.createUpdate(
            """
            UPDATE cluster_tasks_scheduled_node_state s
            SET next_due_at = :nextDueAt,
                lease_expires_at = NULL,
                updated_at = :now
            FROM cluster_tasks_scheduled t
            WHERE s.task_key = t.task_key
              AND s.task_key = :taskKey
              AND s.node_id = :nodeId
              AND t.execution_scope = :eachCapableNode
              AND s.lease_expires_at IS NOT NULL
            """,
        )
            .bind("taskKey", taskKey)
            .bind("eachCapableNode", ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE.dbValue)
            .bind("nodeId", nodeIdentity.nodeId)
            .bind("nextDueAt", nextDueAt)
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

    fun listNodeStates(): List<ClusterScheduledTaskNodeState> = jdbi.withHandle<List<ClusterScheduledTaskNodeState>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT task_key, node_id, next_due_at, lease_expires_at, last_started_at, last_completed_at,
                   last_failed_at, attempt_count, consecutive_failures, last_error, created_at, updated_at
            FROM cluster_tasks_scheduled_node_state
            ORDER BY task_key, node_id
            """,
        )
            .map { rs, _ -> mapNodeState(rs) }
            .list()
    }

    private fun findPerNode(handle: Handle, taskKeys: Collection<String>): List<ClusterScheduledTask> {
        if (taskKeys.isEmpty()) return emptyList()
        return handle.createQuery(
            """
            SELECT ${selectPerNodeColumns()}
            FROM cluster_tasks_scheduled t
            JOIN cluster_tasks_scheduled_node_state s
              ON s.task_key = t.task_key AND s.node_id = :nodeId
            WHERE t.task_key IN (<taskKeys>)
            ORDER BY s.next_due_at, t.task_key
            """,
        )
            .bindList("taskKeys", taskKeys)
            .bind("nodeId", nodeIdentity.nodeId)
            .map { rs, _ -> mapTask(rs) }
            .list()
    }

    /**
     * Deletes all per-node runtime rows for [taskKey]. Per-node state is only
     * meaningful for an enabled EACH_CAPABLE_NODE definition, so it must be cleared
     * when the definition is retired or changes scope to single-owner — otherwise a
     * later reclaim inherits stale `next_due`/attempt/error state and
     * [listNodeStates] shows ghost rows. Called inside an existing transaction.
     */
    private fun deleteNodeState(handle: Handle, taskKey: String) {
        handle.createUpdate("DELETE FROM cluster_tasks_scheduled_node_state WHERE task_key = :taskKey")
            .bind("taskKey", taskKey)
            .execute()
    }

    private fun setEnabled(taskKey: String, tenantKey: TenantKey?, enabled: Boolean): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        val now = EpistolaClock.offsetDateTime()
        val updated = handle.createUpdate(
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

        if (updated && !enabled) {
            handle.createUpdate(
                """
                UPDATE cluster_tasks_scheduled_node_state s
                SET lease_expires_at = NULL,
                    updated_at = :now
                FROM cluster_tasks_scheduled t
                WHERE s.task_key = t.task_key
                  AND s.task_key = :taskKey
                  AND t.tenant_key IS NOT DISTINCT FROM :tenantKey
                """,
            )
                .bind("taskKey", taskKey)
                .bind("tenantKey", tenantKey?.value)
                .bind("now", now)
                .execute()
        }
        updated
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
        executionScope = ClusterScheduledTaskExecutionScope.fromDb(rs.getString("execution_scope")),
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

    private fun mapNodeState(rs: java.sql.ResultSet): ClusterScheduledTaskNodeState = ClusterScheduledTaskNodeState(
        taskKey = rs.getString("task_key"),
        nodeId = rs.getString("node_id"),
        nextDueAt = rs.getObject("next_due_at", OffsetDateTime::class.java),
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
            ${p}execution_scope,
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

    private fun selectPerNodeColumns(): String = """
        t.task_key,
        t.tenant_key,
        t.routing_key,
        t.task_type,
        t.execution_scope,
        t.required_capability,
        t.payload::text AS payload,
        t.schedule_kind,
        t.cron_expression,
        t.interval_ms,
        t.zone_id,
        t.failure_policy,
        t.catch_up_policy,
        t.enabled,
        COALESCE(s.next_due_at, t.next_due_at) AS next_due_at,
        CASE WHEN s.lease_expires_at IS NULL THEN NULL ELSE :nodeId END AS lease_owner_node_id,
        s.lease_expires_at,
        s.last_started_at,
        s.last_completed_at,
        s.last_failed_at,
        COALESCE(s.attempt_count, 0) AS attempt_count,
        COALESCE(s.consecutive_failures, 0) AS consecutive_failures,
        s.last_error,
        t.created_at,
        t.updated_at
    """.trimIndent()

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
        const val LIVENESS_QUERY_TIMEOUT_SECONDS = 5

        // A code task is still "in use" while some node that registered it has a
        // heartbeat (cluster_nodes.last_seen_at) newer than :seenSince. The outer
        // query must alias cluster_tasks_scheduled as `t` and bind :seenSince.
        // Shared by the reconciler scan and the inline retire-on-dispatch check so
        // the two cannot drift.
        const val LIVE_REGISTRATION_EXISTS = """
            EXISTS (
                SELECT 1
                FROM cluster_scheduled_task_registrations r
                JOIN cluster_nodes n ON n.node_id = r.node_id
                WHERE r.task_key = t.task_key
                  AND n.last_seen_at > :seenSince
            )
        """
    }
}
