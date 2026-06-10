package app.epistola.suite.cluster

import app.epistola.suite.mediator.execute
import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.time.OffsetDateTime

@TestPropertySource(
    properties = [
        "epistola.cluster.timers.poll-interval-ms=60000",
        "epistola.cluster.timers.lease-duration-ms=30000",
        "epistola.cluster.timers.retry-delay-ms=30000",
    ],
)
class ClusterTimerRegistryIT : IntegrationTestBase() {

    @Autowired
    private lateinit var registry: ClusterTimerRegistry

    @Autowired
    private lateinit var nodeIdentity: NodeIdentity

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `schedule upserts a durable timer`() {
        deleteTimer("timer-upsert")

        val first = registry.schedule(
            timerKey = "timer-upsert",
            routingKey = "tenant-a",
            timerType = "test",
            dueAt = OffsetDateTime.now().plusMinutes(1),
            payload = mapOf("attempt" to 1),
        )
        val second = registry.schedule(
            timerKey = "timer-upsert",
            routingKey = "tenant-b",
            timerType = "test",
            dueAt = OffsetDateTime.now().plusMinutes(2),
            payload = mapOf("attempt" to 2),
        )

        assertThat(first.timerKey).isEqualTo("timer-upsert")
        assertThat(second.routingKey).isEqualTo("tenant-b")
        assertThat(second.payload["attempt"]).isEqualTo(2)
        assertThat(second.status).isEqualTo(ClusterTimerStatus.SCHEDULED)
    }

    @Test
    fun `schedule command creates a durable timer`() {
        withMediator {
            deleteTimer("timer-command")

            val timer = ScheduleClusterTimer(
                timerKey = "timer-command",
                routingKey = "tenant-command",
                timerType = "test",
                dueAt = OffsetDateTime.now().plusMinutes(1),
                payload = mapOf("source" to "command"),
            ).execute()

            assertThat(timer.timerKey).isEqualTo("timer-command")
            assertThat(timer.payload["source"]).isEqualTo("command")
            assertThat(registry.find("timer-command")?.routingKey).isEqualTo("tenant-command")
        }
    }

    @Test
    fun `cancel deletes a durable timer`() {
        deleteTimer("timer-cancel")
        registry.schedule("timer-cancel", "tenant-a", "test", OffsetDateTime.now().plusMinutes(1))

        val cancelled = registry.cancel("timer-cancel")

        assertThat(cancelled).isTrue()
        assertThat(registry.find("timer-cancel")).isNull()
    }

    @Test
    fun `cancel command deletes a durable timer`() {
        withMediator {
            deleteTimer("timer-cancel-command")
            registry.schedule("timer-cancel-command", "tenant-a", "test", OffsetDateTime.now().plusMinutes(1))

            val cancelled = CancelClusterTimer("timer-cancel-command").execute()

            assertThat(cancelled).isTrue()
            assertThat(registry.find("timer-cancel-command")).isNull()
        }
    }

    @Test
    fun `claim due leases a timer for the current node`() {
        deleteTimer("timer-claim")
        registry.schedule("timer-claim", "tenant-a", "test", OffsetDateTime.now().minusSeconds(1))

        val claimed = registry.claimDue(listOf("timer-claim"))

        assertThat(claimed).hasSize(1)
        assertThat(claimed.single().status).isEqualTo(ClusterTimerStatus.RUNNING)
        assertThat(claimed.single().leaseOwnerNodeId).isEqualTo(nodeIdentity.nodeId)
        assertThat(claimed.single().attemptCount).isEqualTo(1)
    }

    @Test
    fun `complete deletes a running timer owned by the current node`() {
        deleteTimer("timer-complete")
        registry.schedule("timer-complete", "tenant-a", "test", OffsetDateTime.now().minusSeconds(1))
        registry.claimDue(listOf("timer-complete"))

        val completed = registry.complete("timer-complete")

        assertThat(completed).isTrue()
        assertThat(registry.find("timer-complete")).isNull()
    }

    @Test
    fun `expired running timer can be reclaimed`() {
        deleteTimer("timer-expired")
        insertExpiredRunningTimer("timer-expired")

        val claimed = registry.claimDue(listOf("timer-expired"))

        assertThat(claimed).hasSize(1)
        assertThat(claimed.single().leaseOwnerNodeId).isEqualTo(nodeIdentity.nodeId)
        assertThat(claimed.single().attemptCount).isEqualTo(2)
    }

    @Test
    fun `reschedule keeps timer with next due time`() {
        deleteTimer("timer-reschedule")
        registry.schedule("timer-reschedule", "tenant-a", "test", OffsetDateTime.now().minusSeconds(1))
        registry.claimDue(listOf("timer-reschedule"))
        val nextDueAt = OffsetDateTime.now().plusMinutes(5)

        val rescheduled = registry.reschedule("timer-reschedule", nextDueAt, mapOf("phase" to "next"))

        val timer = registry.find("timer-reschedule")
        assertThat(rescheduled).isTrue()
        assertThat(timer?.status).isEqualTo(ClusterTimerStatus.SCHEDULED)
        assertThat(timer?.payload?.get("phase")).isEqualTo("next")
        assertThat(timer?.leaseOwnerNodeId).isNull()
    }

    private fun deleteTimer(timerKey: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("DELETE FROM cluster_timers WHERE timer_key = :timerKey")
                .bind("timerKey", timerKey)
                .execute()
        }
    }

    private fun insertExpiredRunningTimer(timerKey: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO cluster_timers (
                    timer_key, routing_key, timer_type, due_at, payload, status,
                    lease_owner_node_id, lease_expires_at, attempt_count
                )
                VALUES (
                    :timerKey, 'tenant-a', 'test', NOW() - INTERVAL '1 second', '{}'::jsonb, 'running',
                    'dead-node', NOW() - INTERVAL '1 second', 1
                )
                """,
            )
                .bind("timerKey", timerKey)
                .execute()
        }
    }
}
