// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.connection.EnsureConfiguredSupportConnection
import app.epistola.suite.support.connection.TokenSupportConnectionCheckScheduler
import app.epistola.suite.testing.FakeSupportServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Platform → Support connections with a partner connection from the configuration (ADR 0053 step 3) and
 * no connection to Epistola support: an installation can connect to its partner alone.
 */
class SupportPartnerConnectionPageHtmxTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var checks: TokenSupportConnectionCheckScheduler

    @Test
    fun `the platform page lists the partner connection and what it grants, also with Epistola support off`() {
        partner.issuePartnerToken(TOKEN, setOf("requests"))
        checks.checkAll()

        val body = restTemplate.getForEntity("/platform/support", String::class.java).let {
            assertThat(it.statusCode).isEqualTo(HttpStatus.OK)
            it.body!!
        }

        assertThat(body).contains("data-testid=\"support-connections\"").contains("Acme Partner").contains(partner.baseUrl.trimEnd('/'))
            .contains("Connected")
    }

    @Autowired
    private lateinit var registry: app.epistola.suite.support.connection.SupportConnectionRegistry

    private fun post(path: String, form: Map<String, String>, htmx: Boolean = false) = restTemplate.postForEntity(
        path,
        org.springframework.http.HttpEntity(
            org.springframework.util.LinkedMultiValueMap<String, String>().apply { form.forEach { (k, v) -> add(k, v) } },
            org.springframework.http.HttpHeaders().apply {
                contentType = org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED
                if (htmx) set("HX-Request", "true")
            },
        ),
        String::class.java,
    )

    @Test
    fun `an administrator sets what the partner learns of tenants, and an unknown value is refused`() {
        val id = registry.tokenConnections().single { it.endpoint.toString() == partner.baseUrl.trimEnd('/') }.id
        val path = "/platform/support/connections/$id/privacy"

        val saved = post(path, mapOf("tenantIdentity" to "NAMED", "telemetry" to "PSEUDONYMISED"))

        assertThat(saved.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(saved.body).contains("data-testid=\"support-connection-privacy-source\"").contains("set by")
        assertThat(post(path, mapOf("tenantIdentity" to "ANONYMOUS", "telemetry" to "FULL")).statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `a tenant shares support requests with the partner from its Support page, agreeing on its behalf`() {
        val tenant = createTenant("Partner Sharing Page")
        val id = registry.tokenConnections().single { it.endpoint.toString() == partner.baseUrl.trimEnd('/') }.id

        val page = restTemplate.getForEntity("/tenants/${tenant.id.value}/support", String::class.java).body!!
        assertThat(page).contains("data-testid=\"support-connection-sharing\"").contains("Sharing with Acme Partner")

        val path = "/tenants/${tenant.id.value}/support/connections/$id/sharing"
        val refused = post(path, mapOf("requests.choice" to "SHARE"), htmx = true)
        assertThat(refused.body).contains("data-testid=\"support-connection-sharing-error\"")
        assertThat(withMediator { app.epistola.suite.support.requests.consent.GetSupportRequestSyncConsent(tenant.id, id).query() }.inForce).isFalse()

        post(path, mapOf("requests.choice" to "SHARE", "agree" to "true"))

        assertThat(withMediator { app.epistola.suite.support.requests.consent.GetSupportRequestSyncConsent(tenant.id, id).query() }.inForce).isTrue()
        assertThat(withMediator { app.epistola.suite.support.requests.consent.GetSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.inForce).`as`("not with Epistola").isFalse()
    }

    @Test
    fun `an installation administrator sets the partner's preset for every tenant`() {
        val id = registry.tokenConnections().single { it.endpoint.toString() == partner.baseUrl.trimEnd('/') }.id
        assertThat(restTemplate.getForEntity("/platform/support/connections/$id", String::class.java).body).contains("data-testid=\"support-defaults\"")

        post("/platform/support/connections/$id/defaults", mapOf("requests.default" to "SHARE", "agree" to "true"))

        assertThat(withMediator { app.epistola.suite.support.requests.consent.GetSupportRequestSharingDefault(id).query() }.share).isTrue()
        withMediator { app.epistola.suite.support.requests.consent.ClearSupportRequestSharingDefault(id).execute() }
    }

    @Test
    fun `the support desk attaches the partner to a request the inbox handles`() {
        val tenant = createTenant("Partner Attach Page")
        val connectionId = registry.tokenConnections().single { it.endpoint.toString() == partner.baseUrl.trimEnd('/') }.id
        val author = app.epistola.suite.common.ids.UserKey.of("00000000-0000-0000-0000-a77ac4000001")
        ensureUser(author, "partner-attach-page", "partner-attach-page@epistola.test", "Attach Page")
        withMediator { app.epistola.suite.support.requests.consent.GrantSupportRequestSyncConsent(tenant.id, connectionId).execute() }
        val request = app.epistola.suite.common.ids.SupportRequestId(app.epistola.suite.common.ids.SupportRequestKey.generate(), app.epistola.suite.common.ids.TenantId(tenant.id))
        withMediator {
            app.epistola.suite.support.requests.commands.CreateSupportRequest(
                request, "Attach me", "Blank page", app.epistola.suite.support.requests.SupportRequestCategory.BUG,
                app.epistola.suite.support.requests.SupportRequestPriority.MEDIUM, null, null, null, author,
            ).execute()
        }
        val page = "/platform/support-requests/${tenant.id.value}/${request.key.value}"
        assertThat(restTemplate.getForEntity(page, String::class.java).body).contains("data-testid=\"desk-attach\"")

        val attached = post("$page/attach", mapOf("connection" to connectionId.toString()))

        assertThat(attached.body).contains("data-testid=\"desk-attached-connection\"").contains("Acme Partner")
    }

    @Test
    fun `a connection added to register with the installation's key is listed with a page of its own that offers Register, and a token connection has none`() {
        post(
            "/platform/support/connections",
            mapOf("name" to "Registering Partner", "endpoint" to "https://desk.registering.example", "token" to "", "auth" to "PRIVATE_KEY_JWT", "services" to "requests"),
        )
        val id = withMediator { connections.findByEndpoint("https://desk.registering.example")!! }.also {
            assertThat(it.auth).isEqualTo(app.epistola.suite.support.connection.SupportConnectionAuth.PRIVATE_KEY_JWT)
        }.id

        val list = restTemplate.getForEntity("/platform/support", String::class.java).body!!
        assertThat(list.substringAfter("data-connection=\"$id\"").substringBefore("</tr>"))
            .contains("Registering Partner").contains("Not registered").contains("/platform/support/connections/$id").contains("Own key")

        val detail = restTemplate.getForEntity("/platform/support/connections/$id", String::class.java)
        assertThat(detail.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(detail.body).contains("Registering Partner").contains("data-testid=\"support-register-form\"")
            .contains("/platform/support/connections/$id/register")

        val token = registry.tokenConnections().single { it.endpoint.toString() == partner.baseUrl.trimEnd('/') }.id
        val tokenPage = restTemplate.getForEntity("/platform/support/connections/$token", String::class.java).body!!
        assertThat(tokenPage).`as`("a token connection's page shows its state, not a registration").contains("data-testid=\"support-token-connection\"")
            .doesNotContain("data-testid=\"support-register-form\"")
        withMediator { app.epistola.suite.support.connection.RemoveSupportConnection(id).execute() }
    }

    @Autowired
    private lateinit var connections: app.epistola.suite.support.connection.SupportConnections

    @Test
    fun `an installation administrator adds a connection by hand, is told what is missing, and removes it`() {
        assertThat(post("/platform/support/connections/lookup", mapOf("domain" to "")).body).contains("data-testid=\"support-connection-add-error\"")
        val missing = post("/platform/support/connections", mapOf("name" to "Hand Partner", "endpoint" to "https://desk.hand.example", "token" to ""))
        assertThat(missing.body).contains("data-testid=\"support-connection-add-error\"").contains("token")

        val added = post("/platform/support/connections", mapOf("name" to "Hand Partner", "endpoint" to "https://desk.hand.example", "token" to "hand-token", "services" to "requests"))
        assertThat(added.body).`as`("its own page").contains("Hand Partner").contains("data-testid=\"support-token-connection\"")

        val id = registry.tokenConnections().single { it.endpoint.toString() == "https://desk.hand.example" }.id
        post("/platform/support/connections/$id/remove", emptyMap())
        assertThat(registry.tokenConnections().none { it.id == id }).isTrue()
    }

    companion object {
        private const val TOKEN = "partner-page-token"

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
