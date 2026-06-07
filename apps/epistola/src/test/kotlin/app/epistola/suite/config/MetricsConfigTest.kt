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
}
