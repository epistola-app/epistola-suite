// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.desk

import app.epistola.suite.mediator.query
import app.epistola.suite.support.connection.SupportConnectionAuth
import app.epistola.suite.support.connection.SupportConnectionRegistry
import app.epistola.suite.support.connection.SupportConnections
import app.epistola.suite.support.connection.SupportScope
import app.epistola.suite.support.requests.consent.GetSupportRequestSharingDefault
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * A whole support setup declared in configuration (Helm `support.connections`, `support.requests`):
 * a partner that registers with the installation's own key, the services used over it, what tenants
 * share with it, and that it handles new support requests, all without the page.
 */
class ConfiguredSupportSetupIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var connections: SupportConnections

    @Autowired
    private lateinit var registry: SupportConnectionRegistry

    @Autowired
    private lateinit var handling: SupportRequestHandlingPolicy

    @Test
    fun `a configured connection that registers, with its services, its preset, and new requests handled there`() {
        val record = connections.findByEndpoint(ENDPOINT)!!
        assertThat(record.auth).isEqualTo(SupportConnectionAuth.PRIVATE_KEY_JWT)
        assertThat(connections.store(record.id).bearerToken()).`as`("no token for one that registers").isNull()

        val connection = registry.registering(record.id)
        assertThat(connection).`as`("built to register at its endpoint").isNotNull()
        assertThat(connection!!.selectedServices().services).containsExactlyInAnyOrder("requests", "telemetry")

        assertThat(withMediator { GetSupportRequestSharingDefault(record.id).query() }.share).`as`("its preset, applied at startup").isTrue()
        assertThat(handling.connectionForNewRequests()).`as`("handled-by names it by its endpoint").isEqualTo(record.id)
        assertThat(connection.isSelected(SupportScope.REQUESTS)).isTrue()
    }

    companion object {
        private const val ENDPOINT = "https://support.hosting.example"

        @JvmStatic
        @DynamicPropertySource
        fun configuration(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.connections[0].name") { "Hosting Partner" }
            registry.add("epistola.support.connections[0].endpoint") { ENDPOINT }
            registry.add("epistola.support.connections[0].auth") { "register" }
            registry.add("epistola.support.connections[0].services") { "requests,telemetry" }
            registry.add("epistola.support.connections[0].tenant-defaults.requests") { "share" }
            registry.add("epistola.support.requests.handled-by") { ENDPOINT }
        }
    }
}
