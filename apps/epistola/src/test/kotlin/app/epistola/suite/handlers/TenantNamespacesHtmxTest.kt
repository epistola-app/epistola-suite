// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.namespaces.ListNamespaceGrants
import app.epistola.suite.catalog.namespaces.NamespaceGrantSource
import app.epistola.suite.catalog.namespaces.NamespaceName
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.TEST_TENANT_ROLES_HEADER
import app.epistola.suite.testing.TestNamespaces
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
 * The platform page where an administrator manages a tenant's namespaces (ADR 0034, section 3), and
 * the tenant list's way there and its namespace for a new tenant.
 */
class TenantNamespacesHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun page(tenant: TenantKey) = "/platform/tenants/${tenant.value}/namespaces"

    private fun get(url: String, htmx: Boolean = false, roles: String? = null): ResponseEntity<String> = restTemplate.exchange(
        url,
        HttpMethod.GET,
        HttpEntity<Void>(
            HttpHeaders().apply {
                if (htmx) set("HX-Request", "true")
                if (roles != null) set(TEST_TENANT_ROLES_HEADER, roles)
            },
        ),
        String::class.java,
    )

    private fun post(url: String, fields: Map<String, String>): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            set("HX-Request", "true")
        }
        val form = LinkedMultiValueMap<String, String>().apply { fields.forEach { (k, v) -> add(k, v) } }
        return restTemplate.postForEntity(url, HttpEntity(form, headers), String::class.java)
    }

    @Test
    fun `the page lists what the tenant holds, how and since when, and offers to grant one`() {
        val tenant = createTenant("Namespaces Page").id
        val own = TestNamespaces.of(tenant)

        val response = get(page(tenant))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body).contains("Namespaces of Namespaces Page")
        assertThat(body).contains("data-namespace=\"${own.authority}/${own.namespace}\"")
        assertThat(body).contains(NamespaceGrantSource.ADMINISTRATOR.label)
        assertThat(body).contains("data-testid=\"namespace-grant-open\"")
        assertThat(body).contains("data-testid=\"namespace-revoke\"")
    }

    @Test
    fun `the grant dialog defaults the authority, and granting refreshes the list and closes it`() {
        val tenant = createTenant("Namespaces Grant").id

        val dialog = get("${page(tenant)}/new", htmx = true).body!!
        assertThat(dialog).contains("id=\"grant-namespace-dialog\"")
        assertThat(dialog).contains("value=\"exchange.epistola.app\"")

        val granted = post(page(tenant), mapOf("authority" to "gemeente-x.nl", "namespace" to "acme"))

        assertThat(granted.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(granted.headers.getFirst("HX-Trigger")).contains("closeDialog")
        assertThat(granted.body!!).contains("hx-swap-oob=\"outerHTML\"").contains("data-namespace=\"gemeente-x.nl/acme\"")
        val grant = withMediator { ListNamespaceGrants(tenant).query() }.single { it.namespace == "acme" }
        assertThat(grant.authority).isEqualTo("gemeente-x.nl")
        assertThat(grant.grantedThrough).isEqualTo(NamespaceGrantSource.ADMINISTRATOR)
    }

    @Test
    fun `a namespace that is not one, or is held under another authority, is refused in the dialog`() {
        val tenant = createTenant("Namespaces Refused").id
        val own = TestNamespaces.of(tenant)

        val invalid = post(page(tenant), mapOf("authority" to "gemeente-x.nl", "namespace" to "Not A Slug"))
        assertThat(invalid.statusCode.value()).isEqualTo(422)
        assertThat(invalid.headers.getFirst("HX-Retarget")).isEqualTo("#grant-namespace-form")

        val taken = post(page(tenant), mapOf("authority" to "gemeente-x.nl", "namespace" to own.namespace))
        assertThat(taken.statusCode.value()).isEqualTo(422)
        assertThat(taken.body!!).contains("This tenant holds the namespace ${own.namespace} under ${own.authority} already")
    }

    @Test
    fun `revoking one in use is refused with the catalogs named, and one not in use is revoked`() {
        val tenant = createTenant("Namespaces Revoke").id
        val own = TestNamespaces.of(tenant)
        post(page(tenant), mapOf("authority" to "gemeente-x.nl", "namespace" to "acme"))

        val refused = post("${page(tenant)}/revoke", mapOf("authority" to own.authority, "namespace" to own.namespace))
        assertThat(refused.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(refused.body!!).contains("data-testid=\"namespace-revoke-error\"")
            .contains("${own.namespace} is not revoked: the catalog default is named in it. Move or delete it first.")

        val revoked = post("${page(tenant)}/revoke", mapOf("authority" to "gemeente-x.nl", "namespace" to "acme"))
        assertThat(revoked.body!!).doesNotContain("data-namespace=\"gemeente-x.nl/acme\"").doesNotContain("namespace-revoke-error")
        assertThat(withMediator { ListNamespaceGrants(tenant).query() }.map { it.name }).containsExactly(own)
    }

    @Test
    fun `someone who is not a platform administrator is refused`() {
        val tenant = createTenant("Namespaces Forbidden").id

        assertThat(get(page(tenant), roles = "TENANT_ADMINISTRATOR").statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `the tenant list leads to each tenant's namespaces, and a tenant is created with one`() {
        val list = get("/").body!!
        assertThat(list).contains("name=\"namespace\"").contains("name=\"authority\"")

        val key = "tenantnamespaceshtmx-created"
        val created = post("/tenants", mapOf("slug" to key, "name" to "Created With Namespace", "namespace" to "gemeente-x", "authority" to "gemeente-x.nl"))

        assertThat(created.statusCode).`as`(created.body).isEqualTo(HttpStatus.OK)
        assertThat(created.body!!).contains("/platform/tenants/$key/namespaces")
        val tenant = TenantKey.of(key)
        assertThat(withMediator { ListNamespaceGrants(tenant).query() }.map { it.name }).containsExactly(NamespaceName("gemeente-x.nl", "gemeente-x"))
        val default = withMediator { GetCatalog(testCatalog(tenant, CatalogKey.DEFAULT)).query() }!!
        assertThat(default.registry to default.namespace).isEqualTo("gemeente-x.nl" to "gemeente-x")
    }
}
