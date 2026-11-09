// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.applications

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.apikeys.queries.ListApiKeys
import app.epistola.suite.applications.commands.CreateApplication
import app.epistola.suite.applications.queries.GetApplication
import app.epistola.suite.applications.queries.ListApplications
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.TenantRole
import app.epistola.suite.testing.TEST_TENANT_ROLES_HEADER
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.LinkedMultiValueMap

/**
 * The applications page and its keys (ADR 0054): create an application, create and revoke its keys,
 * narrow what it sees, delete it once its keys are revoked.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApplicationHandlerTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `the list shows an empty state, and the old api-keys address leads to it`() {
        val tenant = createTenant("Apps empty").id
        assertThat(get("/tenants/$tenant/applications").body).contains("No applications")
        val legacy = get("/tenants/$tenant/api-keys")
        assertThat(legacy.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(legacy.body).contains("No applications")
    }

    @Test
    fun `creating an application leads to its page, with the roles and visibility chosen`() {
        val tenant = createTenant("Apps create").id
        val response = post(
            "/tenants/$tenant/applications",
            htmx = true,
            "name" to "Case system",
            "description" to "Permit letters",
            "roles" to "DOCUMENT_GENERATOR",
        )
        val application = withMediator { ListApplications(tenant).query() }.single()
        assertThat(response.headers.getFirst("HX-Redirect")).isEqualTo("/tenants/$tenant/applications/${application.id}")
        assertThat(application.roles).containsExactly(TenantRole.DOCUMENT_GENERATOR)
        assertThat(application.seesAllDocuments).`as`("an integration sees only its own documents by default").isFalse

        val page = get("/tenants/$tenant/applications/${application.id}").body!!
        assertThat(page).contains("Case system", "Permit letters", "Only its own", "No active keys")
    }

    @Test
    fun `the create dialog offers only the roles the creator holds, and refuses others`() {
        val tenant = createTenant("Apps roles").id
        val roles = "TENANT_ADMINISTRATOR,CONTENT_VIEWER"
        val dialog = get("/tenants/$tenant/applications/new", htmx = true, roles = roles).body!!
        assertThat(dialog).contains("value=\"TENANT_ADMINISTRATOR\"", "value=\"CONTENT_VIEWER\"")
        assertThat(dialog).doesNotContain("value=\"CONTENT_PUBLISHER\"", "value=\"DOCUMENT_GENERATOR\"")

        val refused = post("/tenants/$tenant/applications", htmx = true, "name" to "Publisher", "roles" to "CONTENT_PUBLISHER", roles = roles)
        assertThat(refused.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(refused.headers.getFirst("HX-Retarget")).isEqualTo("#create-application-form")
        assertThat(withMediator { ListApplications(tenant).query() }).isEmpty()
    }

    @Test
    fun `a key is created on its application's page and revealed once`() {
        val tenant = createTenant("Apps key").id
        val app = withMediator { CreateApplication(tenant, "Case system", roles = setOf(TenantRole.DOCUMENT_GENERATOR)).execute() }

        val dialog = get("/tenants/$tenant/applications/${app.id}/keys/new", htmx = true).body!!
        assertThat(dialog).contains("id=\"create-api-key-dialog\"", "name=\"name\"", "name=\"expiresAt\"")
        assertThat(dialog).`as`("the key takes its application's roles").doesNotContain("name=\"roles\"")

        val created = post("/tenants/$tenant/applications/${app.id}/keys", htmx = true, "name" to "production-2026")
        assertThat(created.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(created.body).contains("epk_", "production-2026")
        assertThat(created.body).`as`("the key list behind the reveal is refreshed").contains("id=\"key-list\"", "hx-swap-oob")

        val keys = withMediator { ListApiKeys(tenant, applicationId = app.id).query() }
        assertThat(keys.single().roles).containsExactly(TenantRole.DOCUMENT_GENERATOR)
    }

    @Test
    fun `a key cannot be created for an application with roles the creator does not hold`() {
        val tenant = createTenant("Apps key ceiling").id
        val app = withMediator { CreateApplication(tenant, "Publisher", roles = setOf(TenantRole.CONTENT_PUBLISHER)).execute() }

        val page = get("/tenants/$tenant/applications/${app.id}", roles = "TENANT_ADMINISTRATOR").body!!
        assertThat(page).contains("data-testid=\"api-key-create-withheld\"").doesNotContain("data-testid=\"api-key-create-open\"")

        val refused = post("/tenants/$tenant/applications/${app.id}/keys", htmx = true, "name" to "k", roles = "TENANT_ADMINISTRATOR")
        assertThat(refused.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(withMediator { ListApiKeys(tenant).query() }).isEmpty()
    }

    @Test
    fun `revoking a key removes it from its application's page`() {
        val tenant = createTenant("Apps revoke").id
        val (app, revoked) = withMediator {
            val app = CreateApplication(tenant, "Case system", roles = setOf(TenantRole.CONTENT_VIEWER)).execute()
            CreateApiKey(tenant, "Live key", applicationId = app.id).execute()
            app to CreateApiKey(tenant, "Soon revoked", applicationId = app.id).execute()
        }

        val response = post("/tenants/$tenant/applications/${app.id}/keys/${revoked.apiKey.id}/delete", htmx = true)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Live key").doesNotContain("Soon revoked")
        assertThat(withMediator { ListApiKeys(tenant).query() }.first { it.name == "Soon revoked" }.enabled).isFalse
    }

    @Test
    fun `an application is narrowed to its own documents from its page`() {
        val tenant = createTenant("Apps narrow").id
        val legacy = withMediator { CreateApiKey(tenant, "Legacy", roles = setOf(TenantRole.CONTENT_VIEWER)).execute() }
        val app = legacy.apiKey.application!!.id
        assertThat(get("/tenants/$tenant/applications/$app").body).contains("Every document")

        val response = post("/tenants/$tenant/applications/$app/documents", htmx = false, "seesAllDocuments" to "false")
        assertThat(response.statusCode.is2xxSuccessful).`as`("the redirect lands back on the application").isTrue
        assertThat(withMediator { GetApplication(tenant, app).query() }!!.seesAllDocuments).isFalse
    }

    @Test
    fun `delete is offered only once every key is revoked`() {
        val tenant = createTenant("Apps delete").id
        val app = withMediator { CreateApplication(tenant, "Case system", roles = setOf(TenantRole.CONTENT_VIEWER)).execute() }
        val key = withMediator { CreateApiKey(tenant, "k", applicationId = app.id).execute() }

        assertThat(get("/tenants/$tenant/applications/${app.id}").body)
            .contains("Revoke this application's active keys before deleting it")
            .doesNotContain("data-testid=\"application-delete-submit\"")

        post("/tenants/$tenant/applications/${app.id}/keys/${key.apiKey.id}/delete", htmx = true)
        assertThat(get("/tenants/$tenant/applications/${app.id}").body).contains("data-testid=\"application-delete-submit\"")

        val deleted = post("/tenants/$tenant/applications/${app.id}/delete", htmx = false)
        assertThat(deleted.statusCode.is2xxSuccessful).isTrue
        assertThat(withMediator { GetApplication(tenant, app.id).query() }).isNull()
    }

    @Test
    fun `another tenant's application is not found`() {
        val tenant = createTenant("Apps mine").id
        val other = createTenant("Apps other").id
        val app = withMediator { CreateApplication(tenant, "Case system", roles = setOf(TenantRole.CONTENT_VIEWER)).execute() }
        assertThat(get("/tenants/$other/applications/${app.id}").statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    private fun get(url: String, htmx: Boolean = false, roles: String? = null): ResponseEntity<String> = restTemplate.exchange(url, HttpMethod.GET, HttpEntity<Void>(headers(htmx, roles)), String::class.java)

    private fun post(url: String, htmx: Boolean, vararg fields: Pair<String, String>, roles: String? = null): ResponseEntity<String> {
        val form = LinkedMultiValueMap<String, String>().apply { fields.forEach { (k, v) -> add(k, v) } }
        val headers = headers(htmx, roles).apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }
        return restTemplate.postForEntity(url, HttpEntity(form, headers), String::class.java)
    }

    private fun headers(htmx: Boolean, roles: String?) = HttpHeaders().apply {
        if (htmx) set("HX-Request", "true")
        if (roles != null) set(TEST_TENANT_ROLES_HEADER, roles)
    }
}
