// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests

import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.common.ids.SupportRequestKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.AutoRegisteringSupportTestBase
import app.epistola.suite.support.connection.EnsureConfiguredSupportConnection
import app.epistola.suite.support.requests.commands.CreateSupportRequest
import app.epistola.suite.support.requests.desk.ResetSupportRequestHandling
import app.epistola.suite.support.requests.desk.SetSupportRequestHandling
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.TEST_PLATFORM_ROLES_HEADER
import org.assertj.core.api.Assertions.assertThat
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
import org.springframework.util.LinkedMultiValueMap

/** Platform → Support requests: the support desk's inbox and one request's page (ADR 0053 §3–4). */
@Isolated("Who handles new support requests applies to the whole installation")
class SupportDeskPageHtmxTest : AutoRegisteringSupportTestBase() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val author = UserKey.of("00000000-0000-0000-0000-de5c00000002")

    @BeforeEach
    fun author() = ensureUser(author, "support-desk-page-author", "support-desk-page@epistola.test", "Page Author")

    @AfterEach
    fun inbox() {
        withMediator { ResetSupportRequestHandling().execute() }
    }

    private fun submit(tenant: Tenant, title: String, serverLogs: String? = null): SupportRequestKey = withMediator {
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
            serverLogs = serverLogs,
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
        assertThat(body).contains("data-testid=\"desk-handling-current\"").contains("New support requests are handled here, in this inbox.")
            .doesNotContain("data-testid=\"request-handling-select\"")
    }

    @Test
    fun `who handles new requests is set on the Epistola support page, and a saved choice can be dropped to follow the configuration again`() {
        val page = get("/platform/support")
        assertThat(page).contains("data-testid=\"support-request-handling\"").contains("data-testid=\"request-handling-select\"")
            .contains("data-testid=\"request-handling-from-configuration\"").doesNotContain("data-testid=\"request-handling-reset\"")

        val saved = post("/platform/support/request-handling", mapOf("connection" to withMediator { EnsureConfiguredSupportConnection().execute() }.toString()))
        assertThat(saved).contains("data-testid=\"request-handling-reset\"").doesNotContain("data-testid=\"request-handling-from-configuration\"")
        assertThat(get("/platform/support-requests")).contains("New support requests are passed on to")

        val reset = post("/platform/support/request-handling/reset", emptyMap())

        assertThat(reset).contains("data-testid=\"request-handling-from-configuration\"").doesNotContain("data-testid=\"request-handling-reset\"")
    }

    @Test
    fun `an installation administrator without the desk role sets who handles new requests`() {
        val page = getAs("/platform/support", "INSTALLATION_ADMINISTRATOR")
        assertThat(page.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(page.body).contains("data-testid=\"request-handling-select\"")
    }

    @Test
    fun `the tenant filter offers every tenant with requests, also one the reader is no member of`() {
        val tenant = createTenant("Desk Filter Stranger")
        submit(tenant, "From a stranger")
        createTenant("Desk Filter Silent")

        val inbox = getAs("/platform/support-requests", "SUPPORT_DESK").body!!

        assertThat(inbox).contains("value=\"${tenant.id.value}\"").contains(">Desk Filter Stranger<").doesNotContain("Desk Filter Silent")
    }

    @Test
    fun `the desk shows an attached server log as the configured default allows, without the kept patterns`() {
        val tenant = createTenant("Desk Page Server Log")
        val key = submit(
            tenant,
            "Server log",
            serverLogs = """[{"level":"ERROR","logger":"app.epistola.suite.Example","message":"Render failed for jan@example.org","pattern":"Render failed for {}"}]""",
        )

        val desk = get("/platform/support-requests/${tenant.id.value}/${key.value}")

        assertThat(desk).contains("Render failed for {}").doesNotContain("jan@example.org").doesNotContain("pattern")
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

    @Autowired
    private lateinit var jdbi: org.jdbi.v3.core.Jdbi

    @Test
    fun `a screenshot on the desk is never served as anything a browser would run`() {
        val tenant = createTenant("Desk Screenshot")
        val key = submit(tenant, "With a stored screenshot")
        val asset = app.epistola.suite.common.ids.SupportRequestAssetKey.generate()
        withMediator {
            app.epistola.suite.support.requests.commands.AddSupportRequestAsset(
                app.epistola.suite.common.ids.SupportRequestAssetId(asset, SupportRequestId(key, TenantId(tenant.id))),
                "<script>alert(1)</script>".toByteArray(),
                "image/png",
                "x.png",
            ).execute()
        }
        // No command stores another type any more: this is what an older or foreign row may hold.
        jdbi.useHandle<Exception> {
            it.createUpdate("UPDATE support_request_assets SET content_type = 'text/html' WHERE id = :a").bind("a", asset.value).execute()
        }

        val response = restTemplate.getForEntity("/platform/support-requests/${tenant.id.value}/${key.value}/assets/${asset.value}", ByteArray::class.java)

        assertThat(response.headers.contentType.toString()).isEqualTo("application/octet-stream")
        assertThat(response.headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff")
    }

    @Test
    fun `a handling choice that names no connection is refused, and nothing is saved`() {
        val before = withMediator { app.epistola.suite.support.requests.desk.GetSupportRequestHandling().query() }
        val body = LinkedMultiValueMap<String, String>().apply { add("connection", "not-a-uuid") }
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }

        val response = restTemplate.postForEntity("/platform/support/request-handling", HttpEntity(body, headers), String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(withMediator { app.epistola.suite.support.requests.desk.GetSupportRequestHandling().query() }.fromConfiguration).isEqualTo(before.fromConfiguration)
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
            .doesNotContain("data-testid=\"request-handling-select\"").doesNotContain("data-testid=\"request-handling-reset\"")
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
}
