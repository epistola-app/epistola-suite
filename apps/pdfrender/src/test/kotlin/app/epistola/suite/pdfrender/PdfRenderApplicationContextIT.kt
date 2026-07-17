package app.epistola.suite.pdfrender

import app.epistola.suite.cluster.ClusterProperties
import app.epistola.suite.documents.batch.JobPoller
import app.epistola.suite.documents.batch.StaleJobRecovery
import app.epistola.suite.testing.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

/**
 * The worker's whole value is that it boots the real render pipeline out of epistola-core with
 * *only* the render capability and nothing else — no UI/REST/MCP/support beans (those modules
 * aren't even on its classpath) and no control-plane schedulers. This proves that context wiring
 * end to end: the render beans are present, and the advertised capability set is render-only, so
 * cluster routing can never hand this node a `suite`-gated maintenance/DDL task.
 *
 * Flyway is re-enabled here purely to provision the fresh per-context Testcontainers database
 * (production keeps `spring.flyway.enabled=false` so the worker runs behind a no-DDL DB user);
 * the deterministic scheduling substrate keeps any autonomous poller/heartbeat threads from
 * starting during the context load.
 */
@SpringBootTest(classes = [PdfRenderApplication::class])
@Import(TestcontainersConfiguration::class)
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=true",
        "epistola.cluster.scheduling-substrate=test",
        "epistola.demo.enabled=false",
    ],
)
@ActiveProfiles("test")
@Tag("integration")
class PdfRenderApplicationContextIT {

    @Autowired
    private lateinit var jobPoller: JobPoller

    @Autowired
    private lateinit var staleJobRecovery: StaleJobRecovery

    @Autowired
    private lateinit var clusterProperties: ClusterProperties

    @Test
    fun `boots the render pipeline advertising the render capability only`() {
        // The two render handlers are wired — the worker can claim and process jobs.
        assertThat(jobPoller).isNotNull
        assertThat(staleJobRecovery).isNotNull

        // Both render tasks are gated on the render capability, not the default suite capability.
        assertThat(jobPoller.jobPollerScheduledTaskDefinition().requiredCapability)
            .isEqualTo(ClusterProperties.PDF_RENDER_CAPABILITY)
        assertThat(staleJobRecovery.staleJobRecoveryScheduledTaskDefinition().requiredCapability)
            .isEqualTo(ClusterProperties.PDF_RENDER_CAPABILITY)

        // This node advertises render only — never `suite` — so it can never be routed a
        // partition-maintenance / content-reaper / quality / hub task (all require `suite`).
        assertThat(clusterProperties.normalizedCapabilities())
            .containsExactly(ClusterProperties.PDF_RENDER_CAPABILITY)
        assertThat(clusterProperties.normalizedCapabilities())
            .doesNotContain(ClusterProperties.DEFAULT_CAPABILITY)
    }
}
