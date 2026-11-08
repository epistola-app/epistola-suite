// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.common.ids.SupportRequestKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.support.requests.commands.CreateSupportRequest
import app.epistola.suite.support.requests.desk.ResetSupportRequestHandling
import app.epistola.suite.support.requests.desk.SetSupportRequestHandling
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.FakeSupportServer
import app.epistola.suite.testing.TEST_PLATFORM_ROLES_HEADER
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.util.LinkedMultiValueMap

/** Platform → Support requests: the support desk's inbox and one request's page (ADR 0053 §3–4). */
@Isolated("Who handles new support requests applies to the whole installation")
class SupportDeskPageHtmxTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val author = UserKey.of("00000000-0000-0000-0000-de5c00000002")

    @BeforeEach
    fun author() = ensureUser(author, "support-desk-page-author", "support-desk-page@epistola.test", "Page Author")

    @AfterEach
    fun inbox() {
        withMediator { ResetSupportRequestHandling().execute() }
    }

    private fun submit(tenant: Tenant, title: String): SupportRequestKey = withMediator {
        CreateSupportRequest(
            id = SupportRequestId(SupportRequestKey.generate(), TenantId(tenant.id)),
            title = title,
            description = "The preview stays blank",
            category = SupportRequestCategory.BUG,
            priority = SupportRequestPriority.HIGH,
            sourceUrl = null,
            consoleLogs = null,
            metadata = null,
            createdBy = author,
        ).execute().id
    }

    private fun get(path: String): String = restTemplate.getForEntity(path, String::class.java).let {
        assertThat(it.statusCode).`as`(path).isEqualTo(HttpStatus.OK)
        it.body!!
    }

    /** Posts the form; the answer is a 303 to the page, which the client follows: that page is returned. */
    private fun post(path: String, form: Map<String, String>): String {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }
        val body = LinkedMultiValueMap<String, String>().apply { form.forEach { (k, v) -> add(k, v) } }
        return restTemplate.postForEntity(path, HttpEntity(body, headers), String::class.java).let {
            assertThat(it.statusCode).`as`(path).isEqualTo(HttpStatus.OK)
            it.body!!
        }
    }

    @Test
    fun `the inbox lists requests across tenants, and links each to its page`() {
        val tenant = createTenant("Desk Page Inbox")
        val key = submit(tenant, "Blank preview")

        val body = get("/platform/support-requests?tenant=${tenant.id.value}")

        assertThat(body).contains("data-testid=\"desk-table\"").contains("Blank preview").contains("Desk Page Inbox").contains("This inbox")
            .contains("/platform/support-requests/${tenant.id.value}/${key.value}")
        assertThat(body).contains("data-testid=\"desk-handling\"").contains("Handled here, in this inbox")
            .contains("data-testid=\"desk-handling-from-configuration\"").doesNotContain("data-testid=\"desk-handling-reset\"")
    }

    @Test
    fun `a saved choice can be dropped to follow the configuration again`() {
        val saved = post("/platform/support-requests/handling", mapOf("connection" to "01900000-0000-7000-8000-000000000001"))
        assertThat(saved).contains("data-testid=\"desk-handling-reset\"").doesNotContain("data-testid=\"desk-handling-from-configuration\"")

        val reset = post("/platform/support-requests/handling/reset", emptyMap())

        assertThat(reset).contains("data-testid=\"desk-handling-from-configuration\"").doesNotContain("data-testid=\"desk-handling-reset\"")
    }

    @Test
    fun `a note shows on the desk only, and a reply reaches the requester's page`() {
        val tenant = createTenant("Desk Page Notes")
        val key = submit(tenant, "Notes")
        val page = "/platform/support-requests/${tenant.id.value}/${key.value}"

        post("$page/messages", mapOf("body" to "Looks like the cache", "note" to "true"))
        post("$page/messages", mapOf("body" to "We are on it"))

        val desk = get(page)
        assertThat(desk).contains("Looks like the cache").contains("Internal note").contains("We are on it")
        assertThat(desk).contains("This inbox handles this request.").contains("data-testid=\"desk-status-select\"")
        val requester = get("/tenants/${tenant.id.value}/support/requests/${key.value}")
        assertThat(requester).contains("We are on it").doesNotContain("Looks like the cache")
    }

    @Test
    fun `a request of a tenant that does not share says why it cannot be passed on, and offers no button`() {
        val tenant = createTenant("Desk Page Private")
        val key = submit(tenant, "Private")

        val desk = get("/platform/support-requests/${tenant.id.value}/${key.value}")

        assertThat(desk).contains("data-testid=\"desk-escalation-blocked\"").doesNotContain("data-testid=\"desk-escalate\"")
    }

    @Test
    fun `the desk sets the status of a request it handles`() {
        val tenant = createTenant("Desk Page Status")
        val key = submit(tenant, "Status")
        val page = "/platform/support-requests/${tenant.id.value}/${key.value}"

        assertThat(post("$page/status", mapOf("status" to "RESOLVED"))).contains("This inbox handles this request.")

        assertThat(get("/platform/support-requests?tenant=${tenant.id.value}&status=RESOLVED")).contains("Status")
    }

    private fun getAs(path: String, platformRoles: String) = restTemplate.exchange(
        path,
        HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders().apply { set(TEST_PLATFORM_ROLES_HEADER, platformRoles) }),
        String::class.java,
    )

    @Test
    fun `the support desk role works the desk, and sees who handles new requests without changing it`() {
        val tenant = createTenant("Desk Page Role")
        val key = submit(tenant, "Role")

        val inbox = getAs("/platform/support-requests?tenant=${tenant.id.value}", "SUPPORT_DESK")
        assertThat(inbox.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(inbox.body).contains("Role").contains("data-testid=\"desk-handling-current\"")
            .doesNotContain("data-testid=\"desk-handling-select\"").doesNotContain("data-testid=\"desk-handling-reset\"")
            .contains("data-testid=\"ws-platform-support-requests\"")
        assertThat(getAs("/platform/support-requests/${tenant.id.value}/${key.value}", "SUPPORT_DESK").statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `without the support desk role there is no desk, also not for an installation administrator`() {
        val tenant = createTenant("Desk Page No Role")
        val key = submit(tenant, "No role")

        assertThat(getAs("/platform/support-requests", "INSTALLATION_ADMINISTRATOR").statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(getAs("/platform/support-requests/${tenant.id.value}/${key.value}", "").statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(getAs("/platform/banner", "INSTALLATION_ADMINISTRATOR").body).doesNotContain("data-testid=\"ws-platform-support-requests\"")
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
