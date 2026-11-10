// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.mediator.query
import app.epistola.suite.testing.FakeSupportServer
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI

/**
 * A support connection the suite signs in to with a token the other side issued, from
 * `epistola.support.connections` (ADR 0053 step 3): a partner, here a stand-in that grants requests
 * and usage. Independent of the connection to Epistola support, which is off here.
 */
class TokenSupportConnectionIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var registry: SupportConnectionRegistry

    @Autowired
    private lateinit var configured: ConfiguredSupportConnections

    @Autowired
    private lateinit var connections: SupportConnections

    @Autowired
    private lateinit var jdbi: Jdbi

    private fun partner(): TokenSupportConnection = registry.tokenConnections().single { it.endpoint.toString() == partner.baseUrl.trimEnd('/') }

    @BeforeEach
    fun reachable() {
        partner.clearFaults()
        partner.issuePartnerToken(TOKEN, setOf("requests", "usage"))
    }

    @Test
    fun `a configured connection is stored at startup, named by its endpoint, with its token encrypted`() {
        val connection = partner()
        val record = connections.find(connection.id)!!

        assertThat(record.name).isEqualTo("Acme Partner")
        assertThat(record.auth).isEqualTo(SupportConnectionAuth.BEARER_TOKEN)
        assertThat(connections.store(connection.id).bearerToken()).isEqualTo(TOKEN)
        val stored = jdbi.withHandle<String, Exception> { it.createQuery("SELECT credentials FROM support_connections WHERE id = :id").bind("id", connection.id).mapTo(String::class.java).one() }
        assertThat(stored).`as`("the token is not stored in clear").doesNotContain(TOKEN)

        configured.apply()
        assertThat(registry.tokenConnections().filter { it.endpoint == connection.endpoint }).`as`("the same endpoint at the next start is the same connection").hasSize(1)
    }

    @Test
    fun `a check learns what it grants, and only the services selected for it are used`() {
        val connection = partner()

        connection.checkNow()

        assertThat(connection.status().state).isInstanceOf(SupportConnectionState.Active::class.java)
        assertThat(connection.hasScope(SupportScope.REQUESTS)).`as`("selected and granted").isTrue()
        assertThat(connection.hasScope(SupportScope.USAGE)).`as`("granted, not selected").isFalse()
        assertThat(connection.hasScope(SupportScope.TELEMETRY)).`as`("neither").isFalse()
        assertThat(partner.bearerTokensSeen.last()).isEqualTo("/api/v1/connection" to TOKEN)
        assertThat(connection.requestsEndpoint()).isEqualTo(URI("${partner.baseUrl.trimEnd('/')}/api/v2/requests"))
    }

    @Test
    fun `a token the other side no longer accepts reads as refused, and nothing is used`() {
        val connection = partner()
        partner.expireTokens()

        connection.checkNow()

        assertThat(connection.status().state).isInstanceOf(SupportConnectionState.Refused::class.java)
        assertThat(connection.hasScope(SupportScope.REQUESTS)).isFalse()
        val listed = withMediator { ListSupportConnections().query() }.single { it.id == connection.id }
        assertThat(listed.standing).isEqualTo(SupportConnectionStanding.REFUSED)
    }

    @Test
    fun `while it cannot be reached its services wait, and the next check that reaches it opens them again`() {
        val connection = partner()
        connection.checkNow()
        partner.fault(app.epistola.suite.testing.FakeSupportFault.unavailable(), "GET", "/api/v1/connection")

        connection.checkNow()

        assertThat(connection.lastContactFailed()).isTrue()
        assertThat(connection.hasScope(SupportScope.REQUESTS)).isFalse()

        partner.clearFaults()
        connection.checkNow()
        assertThat(connection.hasScope(SupportScope.REQUESTS)).isTrue()
    }

    @Test
    fun `a refusal of a scope holds until the next check`() {
        val connection = partner()
        connection.checkNow()

        connection.scopeRefused(SupportScope.REQUESTS)
        assertThat(connection.hasScope(SupportScope.REQUESTS)).isFalse()

        connection.checkNow()
        assertThat(connection.hasScope(SupportScope.REQUESTS)).isTrue()
    }

    @Test
    fun `its client sends the token and refuses any other origin`() {
        val connection = partner()

        assertThatThrownBy { connection.restClient().get().uri("https://elsewhere.example/api/v1/connection").retrieve().toBodilessEntity() }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("Refusing to send")
        connection.restClient().get().uri("/connection").retrieve().toBodilessEntity()
        assertThat(partner.bearerTokensSeen.last()).isEqualTo("/api/v1/connection" to TOKEN)
    }

    @Test
    fun `the platform page lists it with what it grants and what is used`() {
        val connection = partner()
        connection.checkNow()

        val listed = withMediator { ListSupportConnections().query() }.single { it.id == connection.id }

        assertThat(listed.name).isEqualTo("Acme Partner")
        assertThat(listed.standing).isEqualTo(SupportConnectionStanding.ACTIVE)
        assertThat(listed.granted).containsExactly("requests", "usage")
        assertThat(listed.selected).containsExactly("requests")
        assertThat(listed.lastError).isNull()
    }

    companion object {
        private const val TOKEN = "partner-token-0001"

        private val partner = FakeSupportServer()

        @JvmStatic
        @DynamicPropertySource
        fun partnerConnection(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.allow-http") { "true" }
            registry.add("epistola.support.connections[0].name") { "Acme Partner" }
            registry.add("epistola.support.connections[0].endpoint") { partner.baseUrl }
            registry.add("epistola.support.connections[0].token") { TOKEN }
            registry.add("epistola.support.connections[0].services") { "requests" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = partner.close()
    }
}
