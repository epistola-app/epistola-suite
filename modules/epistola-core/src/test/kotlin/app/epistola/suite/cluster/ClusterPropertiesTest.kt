package app.epistola.suite.cluster

import app.epistola.suite.cluster.ClusterProperties.Companion.DEFAULT_CAPABILITY
import app.epistola.suite.cluster.ClusterProperties.Companion.RENDER_CAPABILITY
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The advertised capability set is what routes render jobs: [ClusterProperties.RENDER_CAPABILITY]
 * gates the JobPoller/StaleJobRecovery tasks, so folding it in (or out) here is exactly what
 * makes the suite render by default, lets an operator turn rendering off, and lets a dedicated
 * apps/pdfrender worker advertise render-only.
 */
class ClusterPropertiesTest {

    @Test
    fun `suite renders by default — configured suite capability gains render`() {
        val effective = ClusterProperties(capabilities = listOf(DEFAULT_CAPABILITY))
            .normalizedCapabilities(renderLocally = true)

        assertThat(effective).containsExactlyInAnyOrder(DEFAULT_CAPABILITY, RENDER_CAPABILITY)
    }

    @Test
    fun `render-locally false drops render so a suite node becomes control-plane only`() {
        val effective = ClusterProperties(capabilities = listOf(DEFAULT_CAPABILITY))
            .normalizedCapabilities(renderLocally = false)

        assertThat(effective).containsExactly(DEFAULT_CAPABILITY)
    }

    @Test
    fun `a render-only worker advertises just render`() {
        val effective = ClusterProperties(capabilities = listOf(RENDER_CAPABILITY))
            .normalizedCapabilities(renderLocally = true)

        assertThat(effective).containsExactly(RENDER_CAPABILITY)
    }

    @Test
    fun `render is not duplicated when already configured`() {
        val effective = ClusterProperties(capabilities = listOf(DEFAULT_CAPABILITY, RENDER_CAPABILITY))
            .normalizedCapabilities(renderLocally = true)

        assertThat(effective).containsExactlyInAnyOrder(DEFAULT_CAPABILITY, RENDER_CAPABILITY)
    }

    @Test
    fun `blank capabilities fall back to the default before folding in render`() {
        val effective = ClusterProperties(capabilities = listOf(" ", ""))
            .normalizedCapabilities(renderLocally = true)

        assertThat(effective).containsExactlyInAnyOrder(DEFAULT_CAPABILITY, RENDER_CAPABILITY)
    }
}
