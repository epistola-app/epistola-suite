// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.namespaces.ListNamespaceGrants
import app.epistola.suite.catalog.namespaces.NamespaceGrantSource
import app.epistola.suite.catalog.namespaces.NamespaceName
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.TEST_TENANT_MEMBERSHIPS_HEADER
import app.epistola.suite.testing.TEST_TENANT_ROLES_HEADER
import app.epistola.suite.testing.TestTenantCounter
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
 * Settings, Authorities & namespaces (ADR 0034, amendment of 2026-11-03): the tenant's own
 * administrator sees `local`, the Exchange and each domain with its namespaces, and adds and removes
 * a domain's. The platform page that granted them before is gone.
 */
class TenantAuthoritiesHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun page(tenant: TenantKey) = "/tenants/${tenant.value}/authorities"

    private fun tenant(name: String, vararg namespaces: NamespaceName): TenantKey = withMediator {
        CreateTenant(TenantKey.of("authorities-${TestTenantCounter.next("authorities")}"), name, namespaces.toList()).execute().id
    }

    /** As the tenant's own member with every tenant role, and no platform role: its administrator. */
    private fun memberOf(tenant: TenantKey) = TEST_TENANT_MEMBERSHIPS_HEADER to tenant.value

    private fun get(url: String, htmx: Boolean = false, header: Pair<String, String>? = null): ResponseEntity<String> = restTemplate.exchange(
        url,
        HttpMethod.GET,
        HttpEntity<Void>(
            HttpHeaders().apply {
                if (htmx) set("HX-Request", "true")
                if (header != null) set(header.first, header.second)
            },
        ),
        String::class.java,
    )

    private fun post(url: String, fields: Map<String, String>, header: Pair<String, String>? = null): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            set("HX-Request", "true")
            if (header != null) set(header.first, header.second)
        }
        val form = LinkedMultiValueMap<String, String>().apply { fields.forEach { (k, v) -> add(k, v) } }
        return restTemplate.postForEntity(url, HttpEntity(form, headers), String::class.java)
    }

    @Test
    fun `the page shows local, the Exchange and each domain with its namespaces, how and since when`() {
        val tenant = tenant(
            "Authorities Page",
            NamespaceName("exchange.epistola.app", "acme-public"),
            NamespaceName("gemeente-x.nl", "acme"),
        )

        val response = get(page(tenant), header = memberOf(tenant))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body).contains("Authorities &amp; namespaces")
        assertThat(body).contains("data-testid=\"authority-local\"")
        assertThat(body.substringAfter("data-testid=\"authority-exchange\"").substringBefore("data-testid=\"authority-domain\""))
            .contains("data-namespace=\"exchange.epistola.app/acme-public\"")
        assertThat(body.substringAfter("data-testid=\"authority-domain\"")).contains("data-authority=\"gemeente-x.nl\"")
            .contains("data-namespace=\"gemeente-x.nl/acme\"").contains("Unverified")
        assertThat(body).contains(NamespaceGrantSource.ADMINISTRATOR.label)
        assertThat(body).contains("data-testid=\"namespace-grant-open\"")
        // Settings carries it, for the tenant's administrator.
        assertThat(body).contains("data-testid=\"nav-item-authorities\"")
    }

    @Test
    fun `the add dialog fills in no authority, and adding refreshes the page and closes it`() {
        val tenant = tenant("Authorities Add")

        val dialog = get("${page(tenant)}/new", htmx = true, header = memberOf(tenant)).body!!
        assertThat(dialog).contains("id=\"grant-namespace-dialog\"")
        assertThat(dialog.substringAfter("id=\"grantAuthority\"").substringBefore(">")).contains("value=\"\"").doesNotContain("exchange.epistola.app")

        val added = post(page(tenant), mapOf("authority" to "Gemeente-X.nl", "namespace" to "acme"), header = memberOf(tenant))

        assertThat(added.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(added.headers.getFirst("HX-Trigger")).contains("closeDialog")
        assertThat(added.body!!).contains("hx-swap-oob=\"outerHTML\"").contains("data-namespace=\"gemeente-x.nl/acme\"")
        val grant = withMediator { ListNamespaceGrants(tenant).query() }.single()
        assertThat(grant.name).isEqualTo(NamespaceName("gemeente-x.nl", "acme"))
        assertThat(grant.grantedThrough).isEqualTo(NamespaceGrantSource.ADMINISTRATOR)

        // With one domain held, the dialog starts on it.
        val again = get("${page(tenant)}/new", htmx = true, header = memberOf(tenant)).body!!
        assertThat(again.substringAfter("id=\"grantAuthority\"").substringBefore(">")).contains("value=\"gemeente-x.nl\"")
    }

    @Test
    fun `a namespace under the Exchange, one that is not one, or one held under another domain is refused in the dialog`() {
        val tenant = tenant("Authorities Refused", NamespaceName("gemeente-x.nl", "acme"))

        val exchange = post(page(tenant), mapOf("authority" to "exchange.epistola.app", "namespace" to "other"), header = memberOf(tenant))
        assertThat(exchange.statusCode.value()).isEqualTo(422)
        assertThat(exchange.headers.getFirst("HX-Retarget")).isEqualTo("#grant-namespace-form")
        assertThat(exchange.body!!).contains("come from the tenant&#39;s Epistola Exchange connection")

        val invalid = post(page(tenant), mapOf("authority" to "gemeente-x.nl", "namespace" to "Not A Slug"), header = memberOf(tenant))
        assertThat(invalid.statusCode.value()).isEqualTo(422)

        val taken = post(page(tenant), mapOf("authority" to "hr.gemeente-x.nl", "namespace" to "acme"), header = memberOf(tenant))
        assertThat(taken.statusCode.value()).isEqualTo(422)
        assertThat(taken.body!!).contains("This tenant holds the namespace acme under gemeente-x.nl already")
        assertThat(withMediator { ListNamespaceGrants(tenant).query() }.map { it.name }).containsExactly(NamespaceName("gemeente-x.nl", "acme"))
    }

    @Test
    fun `removing one in use is refused with the catalogs named, and one not in use is removed`() {
        val acme = NamespaceName("gemeente-x.nl", "acme")
        val tenant = tenant("Authorities Remove", acme)
        post(page(tenant), mapOf("authority" to "gemeente-x.nl", "namespace" to "acme-hr"), header = memberOf(tenant))

        val refused = post("${page(tenant)}/revoke", mapOf("authority" to "gemeente-x.nl", "namespace" to "acme"), header = memberOf(tenant))
        assertThat(refused.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(refused.body!!).contains("data-testid=\"namespace-revoke-error\"")
            .contains("acme is not removed: the catalog default is named in it. Move or delete it first.")

        val removed = post("${page(tenant)}/revoke", mapOf("authority" to "gemeente-x.nl", "namespace" to "acme-hr"), header = memberOf(tenant))
        assertThat(removed.body!!).doesNotContain("data-namespace=\"gemeente-x.nl/acme-hr\"").doesNotContain("namespace-revoke-error")
        assertThat(withMediator { ListNamespaceGrants(tenant).query() }.map { it.name }).containsExactly(acme)
    }

    @Test
    fun `someone without INTEGRATIONS_MANAGE is refused, and the platform page that granted namespaces is gone`() {
        val tenant = tenant("Authorities Forbidden")

        assertThat(get(page(tenant), header = TEST_TENANT_ROLES_HEADER to "CONTENT_PUBLISHER").statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(post(page(tenant), mapOf("authority" to "gemeente-x.nl", "namespace" to "acme"), header = TEST_TENANT_ROLES_HEADER to "CONTENT_PUBLISHER").statusCode)
            .isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(withMediator { ListNamespaceGrants(tenant).query() }).isEmpty()

        assertThat(get("/platform/tenants/${tenant.value}/namespaces").statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }
}
