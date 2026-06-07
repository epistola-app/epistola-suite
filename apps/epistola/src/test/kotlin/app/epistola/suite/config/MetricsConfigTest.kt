package app.epistola.suite.config

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MetricsConfigTest {

    @Test
    fun `stamps the fleet identity tags on every meter`() {
        val registry = SimpleMeterRegistry()
        registry.config().meterFilter(
            MetricsConfig().commonTagsFilter(
                nodeId = "node-7",
                installationId = "inst-1",
                environment = "staging",
                version = "1.2.3",
            ),
        )

        val id = registry.counter("epistola.test.counter").id

        assertThat(id.getTag("service")).isEqualTo("epistola-suite")
        assertThat(id.getTag("instance")).isEqualTo("node-7")
        assertThat(id.getTag("installation_id")).isEqualTo("inst-1")
        assertThat(id.getTag("environment")).isEqualTo("staging")
        assertThat(id.getTag("version")).isEqualTo("1.2.3")
    }

    @Test
    fun `strips instance from installation gauges but keeps it on other metrics`() {
        val config = MetricsConfig()
        val registry = SimpleMeterRegistry()
        registry.config().meterFilter(config.commonTagsFilter("node-7", "inst-1", "staging", "1.2.3"))
        registry.config().meterFilter(config.stripInstanceFromInstallationGauges())

        val installation = registry.counter("epistola.installation.templates").id
        assertThat(installation.getTag("instance")).isNull()
        // installation-scoping tags are preserved.
        assertThat(installation.getTag("installation_id")).isEqualTo("inst-1")

        val other = registry.counter("epistola.jobs.claimed.total").id
        assertThat(other.getTag("instance")).isEqualTo("node-7")
    }
}
