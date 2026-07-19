package app.epistola.suite.documents.batch

import app.epistola.suite.cluster.ClusterProperties
import app.epistola.suite.documents.JobPollingProperties
import app.epistola.suite.mediator.Mediator
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

/**
 * Both render tasks must be gated on [ClusterProperties.PDF_RENDER_CAPABILITY], not the default
 * `suite` capability. This is what keeps the render pipeline off suite-only control nodes and
 * routes it onto dedicated apps/pdfrender workers. A silent revert to the default capability
 * would make every control node start rendering again (and would break render-offload
 * deployments), so guard it explicitly.
 */
class RenderTaskCapabilityTest {

    @Test
    fun `job poller requires the render capability`() {
        val poller = JobPoller(
            jdbi = mock(Jdbi::class.java),
            jobExecutor = mock(DocumentGenerationExecutor::class.java),
            properties = JobPollingProperties(),
            batchSizer = mock(AdaptiveBatchSizer::class.java),
            meterRegistry = SimpleMeterRegistry(),
            mediator = mock(Mediator::class.java),
        )

        assertThat(poller.jobPollerScheduledTaskDefinition().requiredCapability)
            .isEqualTo(ClusterProperties.PDF_RENDER_CAPABILITY)
    }

    @Test
    fun `stale-job recovery requires the render capability`() {
        val recovery = StaleJobRecovery(
            jdbi = mock(Jdbi::class.java),
            meterRegistry = SimpleMeterRegistry(),
            staleTimeoutMinutes = 10,
        )

        assertThat(recovery.staleJobRecoveryScheduledTaskDefinition().requiredCapability)
            .isEqualTo(ClusterProperties.PDF_RENDER_CAPABILITY)
    }
}
