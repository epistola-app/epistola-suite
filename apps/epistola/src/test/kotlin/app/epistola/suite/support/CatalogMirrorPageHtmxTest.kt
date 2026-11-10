// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.connection.DefaultSupportConnection
import app.epistola.suite.support.connection.EnsureConfiguredSupportConnection
import app.epistola.suite.support.connection.SupportConnectionStore
import app.epistola.suite.support.mirror.CatalogMirror
import app.epistola.suite.support.mirror.CatalogMirrorSharing
import app.epistola.suite.support.mirror.GetCatalogMirrorSettings
import app.epistola.suite.support.mirror.OptInCatalogMirror
import app.epistola.suite.testing.FakeSupportServer
import app.epistola.suite.testing.TEST_TENANT_ROLES_HEADER
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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

/**
 * `/tenants/{t}/support/mirror` with the support connection on, against a stand-in support service:
 * the statement and explicit choice before opting in, per-catalog policies, and "Sync now" only once
 * Epistola granted the mirror.
 */
class CatalogMirrorPageHtmxTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var store: SupportConnectionStore

    @Autowired
    private lateinit var connection: DefaultSupportConnection

    @BeforeEach
    fun pending() {
        store.forget()
        connection.invalidate()
        connection.checkNow()
    }

    private fun page(tenant: TenantKey): String = restTemplate.getForEntity("/tenants/${tenant.value}/support/mirror", String::class.java).let {
        assertThat(it.statusCode).isEqualTo(HttpStatus.OK)
        it.body!!
    }

    private fun post(tenant: TenantKey, action: String, form: Map<String, String> = emptyMap()): String {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            set("HX-Request", "true")
        }
        val body = LinkedMultiValueMap<String, String>().apply { form.forEach { (k, v) -> add(k, v) } }
        return restTemplate.postForEntity("/tenants/${tenant.value}/support/mirror/$action", HttpEntity(body, headers), String::class.java).let {
            assertThat(it.statusCode).isEqualTo(HttpStatus.OK)
            it.body!!
        }
    }

    private fun tenantWithCatalog(name: String): TenantKey {
        val tenant = createTenant(name).id
        withMediator { CreateCatalog(tenantKey = tenant, id = CatalogKey.of("letters"), name = "Letters").execute() }
        return tenant
    }

    private fun row(html: String, address: String): String = html.substringAfter("data-catalog=\"$address\"").substringBefore("</tr>")

    @Test
    fun `before the tenant shares, the page says so and points to the Support page, the one place to set it`() {
        val tenant = tenantWithCatalog("Mirror page statement")

        val body = page(tenant)

        assertThat(body).contains("data-testid=\"mirror-availability-state\"").contains("Pending")
        assertThat(body).contains("data-testid=\"mirror-where\"").contains("href=\"/tenants/${tenant.value}/support\"")
        assertThat(body).doesNotContain("data-testid=\"mirror-opt-in\"").doesNotContain("data-testid=\"mirror-opt-out\"")
        assertThat(body).doesNotContain("data-testid=\"mirror-sync-now\"")
        assertThat(row(body, "${testCatalog(tenant, "letters").namespace}/letters")).contains("this tenant does not share catalogs")
    }

    @Test
    fun `sharing only marked catalogs, marking one on this page shares only that one`() {
        val tenant = tenantWithCatalog("Mirror page marked")
        val letters = testCatalog(tenant, "letters")
        val address = "${letters.namespace}/letters"
        withMediator { OptInCatalogMirror(tenant, shareByDefault = false, consentVersion = CatalogMirror.CONSENT_VERSION, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        assertThat(row(page(tenant), address)).contains("Not shared: not marked to share")

        val marked = post(tenant, "policy", mapOf("namespace" to letters.namespace, "key" to "letters", "policy" to "SHARE"))

        assertThat(marked).contains("id=\"catalog-mirror\"").doesNotContain("<html")
        assertThat(marked).contains("Saved the policy of $address.")
        assertThat(row(marked, address)).contains("data-sharing=\"SHARED\"")
        assertThat(withMediator { GetCatalogMirrorSettings(tenant, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.catalogs.single { it.catalog == letters }.sharing)
            .isEqualTo(CatalogMirrorSharing.SHARED)
        // Pending: syncing waits for Epistola to activate the installation, and says so.
        assertThat(marked).contains("data-testid=\"mirror-sync-withheld\"").contains("once Epistola has activated this installation")
    }

    @Test
    fun `with the mirror granted, Sync now is offered and requests a background sync`() {
        val tenant = tenantWithCatalog("Mirror page sync")
        support.activate(scopes = setOf("mirror"))
        support.expireTokens()
        connection.checkNow()
        withMediator { OptInCatalogMirror(tenant, shareByDefault = true, consentVersion = CatalogMirror.CONSENT_VERSION, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

        val body = page(tenant)
        assertThat(body).contains("Available").contains("data-testid=\"mirror-sync-now\"")

        val requested = post(tenant, "sync")
        assertThat(requested).contains("Sync requested.").contains("data-testid=\"mirror-sync-requested\"")
    }

    @Test
    fun `a viewer without integrations management is refused`() {
        val tenant = tenantWithCatalog("Mirror page refused")
        val headers = HttpHeaders().apply { set(TEST_TENANT_ROLES_HEADER, "CONTENT_VIEWER") }

        val response = restTemplate.exchange("/tenants/${tenant.value}/support/mirror", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `the tenant support page lists the catalog mirror among its settings`() {
        val tenant = tenantWithCatalog("Mirror page nav")

        val body = restTemplate.getForEntity("/tenants/${tenant.value}/support", String::class.java).body!!

        assertThat(body).contains("data-testid=\"support-settings-mirror\"").contains("href=\"/tenants/${tenant.value}/support/mirror\"")
    }

    companion object {
        private val support = FakeSupportServer()

        @JvmStatic
        @DynamicPropertySource
        fun supportService(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.enabled") { "true" }
            registry.add("epistola.support.privacy.tenant-identity") { "named" }
            // Every service selected, as an administrator would; the selection itself is tested in SupportServiceSelectionIntegrationTest.
            registry.add("epistola.support.services") { "telemetry,requests,mirror" }
            // These tests let the check register; without it, an installation administrator registers.
            registry.add("epistola.support.auto-register") { "true" }
            registry.add("epistola.support.endpoint") { support.baseUrl }
            registry.add("epistola.support.allow-http") { "true" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = support.close()
    }
}
