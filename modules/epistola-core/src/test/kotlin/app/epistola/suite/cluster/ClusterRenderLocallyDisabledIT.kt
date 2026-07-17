package app.epistola.suite.cluster

import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource

/**
 * Proves the render-offload switch is honored end-to-end: with
 * `epistola.generation.render-locally=false` bound from configuration, a `suite` node must
 * advertise `suite` only — no `render` — so it never claims render jobs and the pipeline moves
 * to dedicated apps/pdfrender workers. Complements the pure-logic ClusterPropertiesTest by
 * exercising the actual @Value binding into ClusterNodeRegistry and the persisted cluster_nodes row.
 */
@TestPropertySource(
    properties = [
        "epistola.cluster.capabilities[0]=suite",
        "epistola.generation.render-locally=false",
    ],
)
class ClusterRenderLocallyDisabledIT : IntegrationTestBase() {

    @Autowired
    private lateinit var registry: ClusterNodeRegistry

    @Autowired
    private lateinit var nodeIdentity: NodeIdentity

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `render-locally=false keeps render off a suite node`() {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("DELETE FROM cluster_nodes WHERE node_id = :nodeId")
                .bind("nodeId", nodeIdentity.nodeId)
                .execute()
        }

        val node = registry.heartbeat()

        assertThat(node.capabilities).containsExactly("suite")
    }
}
