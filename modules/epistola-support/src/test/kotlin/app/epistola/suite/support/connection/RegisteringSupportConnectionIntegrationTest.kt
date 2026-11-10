// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.connection.EnsureConfiguredSupportConnection
import app.epistola.suite.testing.FakeSupportServer
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Signing in by `auth` alone (ADR 0053 step 3i): a connection other than Epistola support's that
 * registers with the installation's own key, as a partner running the support service would. It
 * registers at its own endpoint when an administrator asks, is checked and gets its own tokens, and
 * leaves Epistola support's connection as it is.
 */
@Isolated("Removes and stores again the connection epistola.support.* configures")
class RegisteringSupportConnectionIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var registry: SupportConnectionRegistry

    @Autowired
    private lateinit var checks: TokenSupportConnectionCheckScheduler

    @Autowired
    private lateinit var connections: SupportConnections

    @Test
    fun `a connection added to register with the installation's key registers at its endpoint, and is checked and used`() {
        val id = withMediator {
            AddSupportConnection("Partner desk", partner.baseUrl, token = "", services = listOf("requests"), auth = SupportConnectionAuth.PRIVATE_KEY_JWT).execute()
        }
        val connection = registry.registering(id)
        assertThat(connection).`as`("built to register, not as a token connection").isNotNull()
        assertThat(connection!!.status().state).isEqualTo(SupportConnectionState.Unregistered)

        // The scheduled check never registers on its own: an administrator asks.
        checks.checkAll()
        assertThat(partner.registrationRequests).isEmpty()

        withMediator { RegisterSupportConnection(connectionId = id).execute() }
        val pending = connection.status().state as SupportConnectionState.Pending
        assertThat(partner.registrations).containsKey(pending.clientId)
        assertThat(epistola.registrationRequests).`as`("Epistola support's service is not involved").isEmpty()
        assertThat(withMediator { GetSupportConnectionOverview(id).query() }.state).isInstanceOf(SupportConnectionState.Pending::class.java)

        partner.activate(pending.clientId, setOf("requests"))
        checks.checkAll()
        assertThat((connection.status().state as SupportConnectionState.Active).clientId).isEqualTo(pending.clientId)
        assertThat(connection.hasScope(SupportScope.REQUESTS)).isTrue()
        assertThat(connection.accessToken()).isNotBlank()
        assertThat(registry.find(withMediator { EnsureConfiguredSupportConnection().execute() })!!.status().state).`as`("Epistola support's connection is untouched").isEqualTo(SupportConnectionState.Unregistered)

        // Removing it now would leave its registration at the other side: disconnect first.
        assertThatThrownBy { withMediator { RemoveSupportConnection(id).execute() } }.isInstanceOf(SupportConnectionStillRegisteredException::class.java)
        assertThat(registry.find(id)).isNotNull()

        withMediator { DisconnectSupportConnection(connectionId = id).execute() }
        assertThat(connection.status().state).isInstanceOf(SupportConnectionState.Disconnected::class.java)
        assertThat(withMediator { RemoveSupportConnection(id).execute() }).`as`("removed once disconnected").isTrue()
    }

    @Test
    fun `a token connection at Epistola support's endpoint is not taken for the connection epistola support configures`() {
        val configured = withMediator { EnsureConfiguredSupportConnection().execute() }
        withMediator { RemoveSupportConnection(configured).execute() }
        val token = withMediator { AddSupportConnection("Token at the same endpoint", epistola.baseUrl, token = "t", services = emptyList()).execute() }
        try {
            assertThat(connections.configuredId()).`as`("only a connection that registers is Epistola support's").isNull()
            assertThat(connections.configuredStore().exists()).`as`("so its registration is never written into the token connection").isFalse()
            assertThat(registry.find(token)).isInstanceOf(TokenSupportConnection::class.java)
        } finally {
            withMediator { RemoveSupportConnection(token).execute() }
            withMediator { EnsureConfiguredSupportConnection().execute() }
        }
    }

    companion object {
        private val epistola = FakeSupportServer()
        private val partner = FakeSupportServer()

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.enabled") { "true" }
            registry.add("epistola.support.endpoint") { epistola.baseUrl }
            registry.add("epistola.support.allow-http") { "true" }
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            epistola.close()
            partner.close()
        }
    }
}
