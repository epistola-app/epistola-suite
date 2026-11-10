// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.connection.DefaultSupportConnection
import app.epistola.suite.support.connection.EnsureConfiguredSupportConnection
import app.epistola.suite.support.connection.SupportConnectionState
import app.epistola.suite.support.connection.SupportConnectionStore
import app.epistola.suite.support.connection.SupportDisconnectOutcome
import app.epistola.suite.support.sharing.GetSharingRestrictions
import app.epistola.suite.support.sharing.SetSharingRestriction
import app.epistola.suite.support.sharing.SharingRestriction
import app.epistola.suite.support.usage.GetUsageChoice
import app.epistola.suite.support.usage.SetUsageChoice
import app.epistola.suite.support.usage.USAGE_SECTION
import app.epistola.suite.testing.FakeSupportFault
import app.epistola.suite.testing.FakeSupportResponse
import app.epistola.suite.testing.FakeSupportServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.util.LinkedMultiValueMap

/**
 * An installation administrator disconnects from Epistola support on the platform page: the service
 * is told, the registration forgotten, every tenant's choice cleared, and the check does not
 * register again by itself although auto-register is on. Against a stand-in service.
 */
@Isolated("Disconnecting clears the sharing choices of every tenant in the database")
class SupportDisconnectHtmxTest : BaseIntegrationTest() {
    /** The connection epistola.support.* configures, as startup stores it. */
    private fun epistola() = withMediator { EnsureConfiguredSupportConnection().execute() }

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var store: SupportConnectionStore

    @Autowired
    private lateinit var connection: DefaultSupportConnection

    @BeforeEach
    fun activate() {
        support.clearFaults()
        store.forget()
        connection.invalidate()
        connection.resetBackoff()
        connection.checkNow()
        support.activate(scopes = setOf("requests", "mirror"))
        connection.checkNow()
        assertThat(connection.status().state).isInstanceOf(SupportConnectionState.Active::class.java)
    }

    private fun post(path: String, form: Map<String, String> = emptyMap()): String {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            set("HX-Request", "true")
        }
        val body = LinkedMultiValueMap<String, String>().apply { form.forEach { (k, v) -> add(k, v) } }
        return restTemplate.postForEntity(path, HttpEntity(body, headers), String::class.java).let {
            assertThat(it.statusCode).isEqualTo(HttpStatus.OK)
            it.body!!
        }
    }

    @Test
    fun `disconnecting ends the registration at Epistola, forgets its key and clears every tenant's choice`() {
        val tenant = createTenant("Disconnect Clears").id
        withMediator {
            SetUsageChoice(tenant, share = true, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute()
            SetSharingRestriction(USAGE_SECTION, SharingRestriction.REQUIRED, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute()
        }
        val clientId = support.latestRegistration.clientId

        val panel = post("/platform/support/connections/${epistola()}/disconnect")

        assertThat(panel).contains("Disconnected. Epistola support ended this installation").contains("choice of what to share was cleared").contains("data-testid=\"support-disconnected\"")
            .contains("data-testid=\"support-register\"").doesNotContain("data-testid=\"support-disconnect\"")
        assertThat(support.registrations.getValue(clientId).status).isEqualTo("DISCONNECTED")
        val state = connection.status().state as SupportConnectionState.Disconnected
        assertThat(state.outcome).isEqualTo(SupportDisconnectOutcome.CONFIRMED)
        assertThat(store.credentials()).`as`("the key is forgotten").isNull()
        assertThat(withMediator { GetUsageChoice(tenant, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.share).`as`("no choice is left").isNull()
        assertThat(withMediator { GetSharingRestrictions(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.of(USAGE_SECTION)).`as`("nor Required: registering again asks again").isNull()
    }

    @Test
    fun `after disconnecting, the check does not register again by itself, and Register does`() {
        post("/platform/support/connections/${epistola()}/disconnect")
        val registrations = support.registrations.size

        connection.checkNow()

        assertThat(connection.status().state).isInstanceOf(SupportConnectionState.Disconnected::class.java)
        assertThat(support.registrations).`as`("auto-register is on, yet nothing registered").hasSize(registrations)

        val panel = post("/platform/support/connections/${epistola()}/register")

        assertThat(panel).contains("Registered. Epistola support will contact you")
        assertThat(support.registrations).hasSize(registrations + 1)
        assertThat(connection.status().state).isInstanceOf(SupportConnectionState.Pending::class.java)
    }

    @Test
    fun `when Epistola support cannot be reached nothing changes, and the connection can be removed here only`() {
        val clientId = support.latestRegistration.clientId
        support.fault(FakeSupportFault.Respond(FakeSupportResponse.json(503, """{"error":"temporarily_unavailable"}""")), "DELETE", "/api/v1/connection")

        val failed = post("/platform/support/connections/${epistola()}/disconnect")

        assertThat(failed).contains("Not disconnected:").contains("Nothing changed").contains("data-testid=\"support-disconnect-local\"")
        assertThat(connection.status().state).isInstanceOf(SupportConnectionState.Active::class.java)

        val removed = post("/platform/support/connections/${epistola()}/disconnect", mapOf("localOnly" to "true"))

        assertThat(removed).contains("Removed here only").contains("Ask Epistola support to remove the registration")
        assertThat(support.registrations.getValue(clientId).status).`as`("the service was not told").isEqualTo("ACTIVE")
        assertThat((connection.status().state as SupportConnectionState.Disconnected).outcome).isEqualTo(SupportDisconnectOutcome.LOCAL_ONLY)
    }

    @Test
    fun `a registration Epistola revoked is disconnected here without telling the service`() {
        support.revoke()
        connection.invalidate()
        connection.checkNow()
        assertThat(connection.status().state).isInstanceOf(SupportConnectionState.Refused::class.java)
        val deletes = support.requests.count { it.method == "DELETE" }

        val panel = post("/platform/support/connections/${epistola()}/disconnect")

        assertThat(panel).contains("Epistola support no longer accepted this registration")
        assertThat(support.requests.count { it.method == "DELETE" }).isEqualTo(deletes)
    }

    companion object {
        private val support = FakeSupportServer()

        @JvmStatic
        @DynamicPropertySource
        fun supportService(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.enabled") { "true" }
            registry.add("epistola.support.privacy.tenant-identity") { "named" }
            registry.add("epistola.support.services") { "telemetry,requests,mirror" }
            registry.add("epistola.support.auto-register") { "true" }
            registry.add("epistola.support.endpoint") { support.baseUrl }
            registry.add("epistola.support.allow-http") { "true" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = support.close()
    }
}
