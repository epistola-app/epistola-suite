package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.time.OffsetDateTime

@SpringBootTest(classes = [EpistolaSuiteApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
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
}
