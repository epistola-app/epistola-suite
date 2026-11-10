// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.connection.EnsureConfiguredSupportConnection
import app.epistola.suite.support.privacy.SupportPrivacy
import app.epistola.suite.support.privacy.TelemetryDetail
import app.epistola.suite.support.privacy.TenantIdentity
import app.epistola.suite.testing.FakeSupportResponse
import app.epistola.suite.testing.FakeSupportServer
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID

/**
 * An installation administrator adds, changes and removes support connections (ADR 0053 §1a): from the
 * preset a server publishes in its discovery document, or by hand. What an administrator saved, the
 * configuration leaves alone.
 */
class ManageSupportConnectionsIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var connections: SupportConnections

    @Autowired
    private lateinit var registry: SupportConnectionRegistry

    @Autowired
    private lateinit var configured: ConfiguredSupportConnections

    @Autowired
    private lateinit var privacy: SupportPrivacy

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `a preset a server publishes fills in the connection, and an address that cannot be used says why`() {
        server.route("GET", "/.well-known/epistola.json") {
            FakeSupportResponse.json(200, """{"support":{"name":"Preset Partner","endpoint":"https://desk.preset.example/epistola","services":["requests","usage"],"tenantIdentity":"named"}}""")
        }

        val preset = withMediator { DiscoverSupportConnectionPreset(server.baseUrl).query() }

        assertThat(preset).isEqualTo(SupportConnectionPreset("Preset Partner", "https://desk.preset.example/epistola", listOf("requests", "usage"), TenantIdentity.NAMED, null))
        assertThatThrownBy { withMediator { DiscoverSupportConnectionPreset("not a domain").query() } }.isInstanceOf(SupportConnectionPresetUnavailableException::class.java)
        assertThatThrownBy { withMediator { DiscoverSupportConnectionPreset("ftp://partner.example").query() } }.isInstanceOf(SupportConnectionPresetUnavailableException::class.java)
    }

    @Test
    fun `an added connection keeps its token encrypted, the services selected and its own privacy`() {
        val id = withMediator {
            AddSupportConnection("Added Partner", "https://desk.added.example/", "added-token", listOf("requests"), TenantIdentity.NAMED, TelemetryDetail.FULL).execute()
        }

        val connection = registry.tokenConnections().single { it.id == id }
        assertThat(connection.endpoint.toString()).isEqualTo("https://desk.added.example")
        assertThat(connections.store(id).bearerToken()).isEqualTo("added-token")
        assertThat(connection.isSelected(SupportScope.REQUESTS)).isTrue()
        assertThat(connection.isSelected(SupportScope.USAGE)).isFalse()
        assertThat(privacy.forConnection(id).tenantIdentity).isEqualTo(TenantIdentity.NAMED)
        assertThatThrownBy { withMediator { AddSupportConnection("Again", "https://desk.added.example", "t", emptyList()).execute() } }
            .isInstanceOf(SupportConnectionExistsException::class.java)

        withMediator { RemoveSupportConnection(id).execute() }
        assertThat(connections.find(id)).isNull()
    }

    @Test
    fun `a configured connection an administrator saved is left alone by the configuration at the next start`() {
        val id = registry.tokenConnections().single { it.endpoint.toString() == CONFIGURED }.id

        withMediator { UpdateSupportConnection(id, "Renamed here", listOf("usage"), token = "replaced-token").execute() }
        configured.apply()

        assertThat(connections.find(id)!!.name).isEqualTo("Renamed here")
        assertThat(connections.store(id).bearerToken()).isEqualTo("replaced-token")
        val fromConfiguration = jdbi.withHandle<Boolean, Exception> { it.createQuery("SELECT from_configuration FROM support_connections WHERE id = :id").bind("id", id).mapTo(Boolean::class.java).one() }
        assertThat(fromConfiguration).isFalse()
    }

    @Test
    fun `Epistola support's connection is removed like any other and stored again as the configuration asks, and an unknown one changes nothing`() {
        val epistola = withMediator { EnsureConfiguredSupportConnection().execute() }

        assertThat(withMediator { RemoveSupportConnection(epistola).execute() }).isTrue()
        assertThat(connections.find(epistola)).isNull()

        // What the next start does while epistola.support.enabled is on: stored anew, at the same endpoint.
        val again = withMediator { EnsureConfiguredSupportConnection().execute() }
        assertThat(again).isNotEqualTo(epistola)
        assertThat(connections.find(again)!!.endpoint).isEqualTo(connections.configuredEndpoint().toString())

        assertThat(withMediator { RemoveSupportConnection(UUID.randomUUID()).execute() }).isFalse()
    }

    @Test
    fun `while support is off, Epistola support's connection that is still registered is not removed`() {
        // Support is off (epistola.support.enabled unset): Epistola support's connection, stored while it
        // was on, still holds its registration. Nothing registers while it is off, so the store says so.
        val id = withMediator { AddSupportConnection("Epistola support", connections.configuredEndpoint().toString(), token = "", services = emptyList(), auth = SupportConnectionAuth.PRIVATE_KEY_JWT).execute() }
        val store = connections.store(id)
        store.saveCredentials(StoredSupportCredentials(issuer = connections.configuredEndpoint().toString(), clientId = "client-1", privateJwk = "{}"))
        store.saveStatus(StoredSupportStatus(issuer = connections.configuredEndpoint().toString(), clientId = "client-1", state = StoredConnectionState.ACTIVE))
        try {
            assertThat(registry.find(id)!!.status().state).isEqualTo(SupportConnectionState.Disabled)

            assertThatThrownBy { withMediator { RemoveSupportConnection(id).execute() } }.isInstanceOf(SupportConnectionStillRegisteredException::class.java)
            assertThat(withMediator { ListSupportConnections().query() }.single { it.id == id }.canRemove).isFalse()
        } finally {
            if (connections.find(id) != null) {
                store.forget()
                withMediator { RemoveSupportConnection(id).execute() }
            }
        }
    }

    companion object {
        private const val CONFIGURED = "https://desk.configured.example"

        private val server = FakeSupportServer()

        @JvmStatic
        @DynamicPropertySource
        fun connections(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.allow-http") { "true" }
            registry.add("epistola.support.connections[0].name") { "Configured Partner" }
            registry.add("epistola.support.connections[0].endpoint") { CONFIGURED }
            registry.add("epistola.support.connections[0].token") { "configured-token" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = server.close()
    }
}
