// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource

/**
 * The actuator (health details, info, Prometheus metrics with tenant and template keys) is served on
 * its own port in every profile, so an installation that exposes the application port through an
 * ingress never publishes it. Probes stay on the application port at `/livez` and `/readyz`.
 *
 * Binds the packaged `application.yaml` as an installation without a profile would get it.
 */
@Tag("unit")
class ManagementPortConfigTest {

    private val environment = StandardEnvironment().apply {
        YamlPropertySourceLoader().load("application", ClassPathResource("application.yaml"))
            .filter { it.getProperty("spring.config.activate.on-profile") == null }
            .forEach { propertySources.addLast(it) }
    }

    @Test
    fun `without a profile the actuator serves on the management port, not the application port`() {
        assertThat(environment.getProperty("management.server.port")).isEqualTo("4040")
        assertThat(environment.getProperty("server.port")).isNotEqualTo("4040")
    }

    @Test
    fun `probes stay on the application port`() {
        assertThat(environment.getProperty("management.endpoint.health.probes.add-additional-paths")).isEqualTo("true")
    }
}
