package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ClusterStatusHandlerTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var nodeIdentity: NodeIdentity

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `GET dashboard renders current and stale cluster nodes`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Cluster Status Tenant")
            deleteNode("stale-cluster-node")
            insertNode("stale-cluster-node", OffsetDateTime.now().minusMinutes(2))
        }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${tenant.id}/cluster",
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            assertThat(body).contains("Cluster")
            assertThat(body).contains(nodeIdentity.nodeId)
            assertThat(body).contains("current")
            assertThat(body).contains("stale-cluster-node")
            assertThat(body).contains("Stale after")
            assertThat(body).contains("hx-trigger=\"every 2s\"")
        }
    }

    @Test
    fun `GET refresh under HTMX returns the inner fragment without the layout shell`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Cluster Refresh Tenant")
        }

        whenever {
            val headers = HttpHeaders()
            headers.set("HX-Request", "true")
            restTemplate.exchange(
                "/tenants/${tenant.id}/cluster/refresh",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            assertThat(body).doesNotContain("<html")
            assertThat(body).doesNotContain("app-nav")
            assertThat(body).contains("id=\"cluster-results\"")
            assertThat(body).contains(nodeIdentity.nodeId)
        }
    }

    @Test
    fun `GET dashboard renders effective per-node next due for each capable node tasks`() = fixture {
        lateinit var tenant: Tenant
        val taskKey = "test.each-node-effective-due"
        val definitionDueAt = OffsetDateTime.of(2000, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC)
        val nodeDueAt = OffsetDateTime.of(2099, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC)

        given {
            tenant = tenant("Cluster Effective Due Tenant")
            deleteScheduledTask(taskKey)
            insertEachNodeScheduledTask(taskKey, definitionDueAt)
            insertScheduledTaskNodeState(taskKey, nodeIdentity.nodeId, nodeDueAt)
        }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${tenant.id}/cluster",
                String::class.java,
            )
        }

        then {
            deleteScheduledTask(taskKey)
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            assertThat(body).contains(taskKey)
            assertThat(body).contains("2099-01-02 03:04:05")
            assertThat(body).doesNotContain("2000-01-02 03:04:05")
        }
    }

    private fun deleteNode(nodeId: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("DELETE FROM cluster_nodes WHERE node_id = :nodeId")
                .bind("nodeId", nodeId)
                .execute()
        }
    }

    private fun insertNode(nodeId: String, lastSeenAt: OffsetDateTime) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO cluster_nodes (node_id, capabilities, joined_at, last_seen_at, metadata)
                VALUES (:nodeId, '["suite"]'::jsonb, :joinedAt, :lastSeenAt, '{}'::jsonb)
                ON CONFLICT (node_id) DO UPDATE
                SET last_seen_at = EXCLUDED.last_seen_at
                """,
            )
                .bind("nodeId", nodeId)
                .bind("joinedAt", lastSeenAt)
                .bind("lastSeenAt", lastSeenAt)
                .execute()
        }
    }

    private fun deleteScheduledTask(taskKey: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("DELETE FROM cluster_tasks_scheduled WHERE task_key = :taskKey")
                .bind("taskKey", taskKey)
                .execute()
        }
    }

    private fun insertEachNodeScheduledTask(
        taskKey: String,
        nextDueAt: OffsetDateTime,
    ) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO cluster_tasks_scheduled (
                    task_key, routing_key, task_type, required_capability, payload,
                    schedule_kind, interval_ms, enabled, next_due_at, execution_scope
                )
                VALUES (
                    :taskKey, 'system:test.each-node-effective-due', 'test.each-node-effective-due',
                    'suite', '{}'::jsonb, 'fixed_delay', 60000, true, :nextDueAt, 'each_capable_node'
                )
                """,
            )
                .bind("taskKey", taskKey)
                .bind("nextDueAt", nextDueAt)
                .execute()
        }
    }

    private fun insertScheduledTaskNodeState(
        taskKey: String,
        nodeId: String,
        nextDueAt: OffsetDateTime,
    ) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO cluster_tasks_scheduled_node_state (task_key, node_id, next_due_at)
                VALUES (:taskKey, :nodeId, :nextDueAt)
                """,
            )
                .bind("taskKey", taskKey)
                .bind("nodeId", nodeId)
                .bind("nextDueAt", nextDueAt)
                .execute()
        }
    }
}
