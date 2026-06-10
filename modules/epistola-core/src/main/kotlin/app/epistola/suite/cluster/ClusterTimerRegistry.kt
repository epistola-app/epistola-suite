package app.epistola.suite.cluster

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.observability.NodeIdentity
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

@Component
class ClusterTimerRegistry(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val nodeIdentity: NodeIdentity,
    private val properties: ClusterProperties,
) {
    private val payloadType = objectMapper.typeFactory.constructMapType(Map::class.java, String::class.java, Any::class.java)

    fun schedule(
        timerKey: String,
        routingKey: String,
        timerType: String,
        dueAt: OffsetDateTime,
        payload: Map<String, Any?> = emptyMap(),
        tenantKey: TenantKey? = null,
    ): ClusterTimer {
        val payloadJson = objectMapper.writeValueAsString(payload)
        return jdbi.withHandle<ClusterTimer, Exception> { handle ->
            handle.createQuery(
                """
                INSERT INTO cluster_timers (timer_key, tenant_key, routing_key, timer_type, due_at, payload, status, updated_at)
                VALUES (:timerKey, :tenantKey, :routingKey, :timerType, :dueAt, :payload::jsonb, 'scheduled', NOW())
                ON CONFLICT (timer_key) DO UPDATE
                SET tenant_key = EXCLUDED.tenant_key,
                    routing_key = EXCLUDED.routing_key,
                    timer_type = EXCLUDED.timer_type,
                    due_at = EXCLUDED.due_at,
                    payload = EXCLUDED.payload,
                    status = 'scheduled',
                    lease_owner_node_id = NULL,
                    lease_expires_at = NULL,
                    last_error = NULL,
                    updated_at = NOW()
                RETURNING ${selectColumns()}
                """,
            )
                .bind("timerKey", timerKey)
                .bind("tenantKey", tenantKey?.value)
                .bind("routingKey", routingKey)
                .bind("timerType", timerType)
                .bind("dueAt", dueAt)
                .bind("payload", payloadJson)
                .map { rs, _ -> mapTimer(rs) }
                .one()
        }
    }

    fun dueCandidates(limit: Int = properties.timers.candidateScanSize): List<ClusterTimer> = jdbi.withHandle<List<ClusterTimer>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT ${selectColumns()}
            FROM cluster_timers
            WHERE due_at <= NOW()
              AND (
                  (status = 'scheduled' AND (lease_owner_node_id IS NULL OR lease_expires_at < NOW()))
                  OR (status = 'running' AND lease_expires_at < NOW())
              )
            ORDER BY due_at, timer_key
            LIMIT :limit
            """,
        )
            .bind("limit", limit)
            .map { rs, _ -> mapTimer(rs) }
            .list()
    }

    fun claimDue(timerKeys: Collection<String>, batchSize: Int = properties.timers.batchSize): List<ClusterTimer> {
        if (timerKeys.isEmpty()) return emptyList()
        return jdbi.inTransaction<List<ClusterTimer>, Exception> { handle ->
            handle.createQuery(
                """
                WITH due AS (
                    SELECT timer_key
                    FROM cluster_timers
                    WHERE timer_key IN (<timerKeys>)
                      AND due_at <= NOW()
                      AND (
                          (status = 'scheduled' AND (lease_owner_node_id IS NULL OR lease_expires_at < NOW()))
                          OR (status = 'running' AND lease_expires_at < NOW())
                      )
                    ORDER BY due_at, timer_key
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE cluster_timers t
                SET status = 'running',
                    lease_owner_node_id = :nodeId,
                    lease_expires_at = NOW() + (:leaseDurationMs * INTERVAL '1 millisecond'),
                    last_started_at = NOW(),
                    attempt_count = attempt_count + 1,
                    updated_at = NOW()
                FROM due
                WHERE t.timer_key = due.timer_key
                RETURNING ${selectColumns("t")}
                """,
            )
                .bindList("timerKeys", timerKeys)
                .bind("batchSize", batchSize)
                .bind("nodeId", nodeIdentity.nodeId)
                .bind("leaseDurationMs", properties.timers.leaseDurationMs)
                .map { rs, _ -> mapTimer(rs) }
                .list()
        }
    }

    fun complete(timerKey: String): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createUpdate(
            """
            DELETE FROM cluster_timers
            WHERE timer_key = :timerKey
              AND lease_owner_node_id = :nodeId
              AND status = 'running'
            """,
        )
            .bind("timerKey", timerKey)
            .bind("nodeId", nodeIdentity.nodeId)
            .execute() == 1
    }

    fun cancel(timerKey: String, tenantKey: TenantKey? = null): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createUpdate(
            """
            DELETE FROM cluster_timers
            WHERE timer_key = :timerKey
              AND tenant_key IS NOT DISTINCT FROM :tenantKey
            """,
        )
            .bind("timerKey", timerKey)
            .bind("tenantKey", tenantKey?.value)
            .execute() == 1
    }

    fun reschedule(timerKey: String, nextDueAt: OffsetDateTime, payload: Map<String, Any?>? = null): Boolean {
        val payloadJson = payload?.let { objectMapper.writeValueAsString(it) }
        return jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE cluster_timers
                SET status = 'scheduled',
                    due_at = :nextDueAt,
                    payload = COALESCE(:payload::jsonb, payload),
                    lease_owner_node_id = NULL,
                    lease_expires_at = NULL,
                    last_completed_at = NOW(),
                    last_error = NULL,
                    updated_at = NOW()
                WHERE timer_key = :timerKey
                  AND lease_owner_node_id = :nodeId
                  AND status = 'running'
                """,
            )
                .bind("timerKey", timerKey)
                .bind("nodeId", nodeIdentity.nodeId)
                .bind("nextDueAt", nextDueAt)
                .bind("payload", payloadJson)
                .execute() == 1
        }
    }

    fun retryAfterFailure(timerKey: String, retryAt: OffsetDateTime, error: String): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createUpdate(
            """
            UPDATE cluster_timers
            SET status = 'scheduled',
                due_at = :retryAt,
                lease_owner_node_id = NULL,
                lease_expires_at = NULL,
                last_error = :error,
                updated_at = NOW()
            WHERE timer_key = :timerKey
              AND lease_owner_node_id = :nodeId
              AND status = 'running'
            """,
        )
            .bind("timerKey", timerKey)
            .bind("nodeId", nodeIdentity.nodeId)
            .bind("retryAt", retryAt)
            .bind("error", error.take(MAX_ERROR_LENGTH))
            .execute() == 1
    }

    fun find(timerKey: String): ClusterTimer? = jdbi.withHandle<ClusterTimer?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT ${selectColumns()}
            FROM cluster_timers
            WHERE timer_key = :timerKey
            """,
        )
            .bind("timerKey", timerKey)
            .map { rs, _ -> mapTimer(rs) }
            .findOne()
            .orElse(null)
    }

    private fun mapTimer(rs: java.sql.ResultSet): ClusterTimer = ClusterTimer(
        timerKey = rs.getString("timer_key"),
        tenantKey = rs.getString("tenant_key")?.let { TenantKey.of(it) },
        routingKey = rs.getString("routing_key"),
        timerType = rs.getString("timer_type"),
        dueAt = rs.getObject("due_at", OffsetDateTime::class.java),
        payload = readPayload(rs.getString("payload")),
        status = ClusterTimerStatus.fromDb(rs.getString("status")),
        leaseOwnerNodeId = rs.getString("lease_owner_node_id"),
        leaseExpiresAt = rs.getObject("lease_expires_at", OffsetDateTime::class.java),
        lastStartedAt = rs.getObject("last_started_at", OffsetDateTime::class.java),
        lastCompletedAt = rs.getObject("last_completed_at", OffsetDateTime::class.java),
        attemptCount = rs.getInt("attempt_count"),
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
            ${p}timer_key,
            ${p}tenant_key,
            ${p}routing_key,
            ${p}timer_type,
            ${p}due_at,
            ${p}payload::text AS payload,
            ${p}status,
            ${p}lease_owner_node_id,
            ${p}lease_expires_at,
            ${p}last_started_at,
            ${p}last_completed_at,
            ${p}attempt_count,
            ${p}last_error,
            ${p}created_at,
            ${p}updated_at
        """.trimIndent()
    }

    private companion object {
        const val MAX_ERROR_LENGTH = 2_000
    }
}
