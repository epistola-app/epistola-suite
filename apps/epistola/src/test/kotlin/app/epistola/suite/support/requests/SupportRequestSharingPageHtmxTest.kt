// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.connection.DefaultSupportConnection
import app.epistola.suite.support.connection.SupportConnectionStore
import app.epistola.suite.support.requests.consent.GetSupportRequestSyncConsent
import app.epistola.suite.support.requests.consent.GrantSupportRequestSyncConsent
import app.epistola.suite.testing.FakeSupportServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
 * Support → Feedback sharing with the support connection on, against a stand-in support service:
 * what is sent and to whom, why consent cannot be given yet, and giving and withdrawing it.
 */
class SupportRequestSharingPageHtmxTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var store: SupportConnectionStore

    @Autowired
    private lateinit var connection: DefaultSupportConnection

    @BeforeEach
    fun forget() {
        store.forget()
        connection.invalidate()
    }

    private fun page(tenantKey: String): String = restTemplate.getForEntity("/tenants/$tenantKey/support/request-sharing", String::class.java).let {
        assertThat(it.statusCode).isEqualTo(HttpStatus.OK)
        it.body!!
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
    fun `the page says what is sent, and why consent cannot be given while waiting to be activated`() {
        val tenant = createTenant("Sharing Pending")
        connection.checkNow()

        val body = page(tenant.id.value)

        assertThat(body).contains("data-testid=\"support-request-sharing-fields\"")
        assertThat(body).contains("including the query string").contains("browser console log").contains("screenshot")
            .contains("name and email address")
        assertThat(body).contains("data-testid=\"support-request-sharing-withheld-pending\"")
        assertThat(body).doesNotContain("data-testid=\"support-request-sharing-grant\"").doesNotContain("data-testid=\"support-request-sharing-withdraw\"")
        assertThat(body).contains("Requests submitted before that stay in")
    }

    @Test
    fun `without the support request service the page says so`() {
        val tenant = createTenant("Sharing NoService")
        connection.checkNow()
        support.activate(scopes = setOf("telemetry"))
        connection.checkNow()

        val body = page(tenant.id.value)

        assertThat(body).contains("data-testid=\"support-request-sharing-withheld-no-service\"")
        assertThat(body).doesNotContain("data-testid=\"support-request-sharing-grant\"")
    }

    @Test
    fun `once activated with the support request service, the page shows the consent and points to the Support page to change it`() {
        val tenant = createTenant("Sharing Ready")
        connection.checkNow()
        support.activate(scopes = setOf("requests"))
        connection.checkNow()

        val ready = page(tenant.id.value)
        assertThat(ready).contains("which has activated this installation").contains(support.baseUrl)
        assertThat(ready).doesNotContain("data-testid=\"support-request-sharing-withheld")
        assertThat(ready).doesNotContain("data-testid=\"support-request-sharing-grant\"").doesNotContain("data-testid=\"support-request-sharing-withdraw\"")
        assertThat(ready).contains("data-testid=\"support-request-sharing-support-link\"").contains("href=\"/tenants/${tenant.id.value}/support\"")

        withMediator { GrantSupportRequestSyncConsent(tenant.id).execute() }
        assertThat(page(tenant.id.value)).contains("This tenant consented: new support requests are sent to Epistola support.")
            .contains("data-testid=\"support-request-sharing-consented\"")
    }

    @Test
    fun `the tenant support page links SupportRequest sharing`() {
        val tenant = createTenant("Sharing Nav")

        val body = restTemplate.getForEntity("/tenants/${tenant.id.value}/support", String::class.java).body!!

        assertThat(body).contains("data-testid=\"support-settings-support-request-sharing\"").contains("/tenants/${tenant.id.value}/support/request-sharing")
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
