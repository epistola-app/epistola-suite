// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.connection.DefaultSupportConnection
import app.epistola.suite.support.connection.SupportConnectionStore
import app.epistola.suite.support.mirror.CatalogMirror
import app.epistola.suite.support.mirror.CatalogMirrorConfidentialException
import app.epistola.suite.support.mirror.GetCatalogMirrorSettings
import app.epistola.suite.support.mirror.OptInCatalogMirror
import app.epistola.suite.support.privacy.SupportPrivacy
import app.epistola.suite.support.privacy.TenantReferences
import app.epistola.suite.testing.FakeSupportServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
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

/**
 * What Epistola sees of the tenants (ADR 0052 §1), with the configuration's defaults: confidential
 * tenants and pseudonymised telemetry. Confidential, a tenant is known by its reference and the
 * catalog mirror is unavailable; named, the mirror can be used again.
 */
@Isolated("The privacy settings apply to the whole installation")
class SupportPrivacyHtmxTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var store: SupportConnectionStore

    @Autowired
    private lateinit var connection: DefaultSupportConnection

    @BeforeEach
    fun activate() {
        store.forget()
        connection.invalidate()
        connection.checkNow()
        support.activate(scopes = setOf("requests", "mirror"))
        connection.checkNow()
    }

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var privacy: SupportPrivacy

    @Autowired
    private lateinit var references: TenantReferences

    @AfterEach
    fun backToConfiguration() {
        // No command removes saved settings (an administrator only replaces them); the next test class
        // must find the configuration in force, not what this one saved.
        jdbi.useHandle<Exception> { it.createUpdate("DELETE FROM app_metadata WHERE key = :key").bind("key", SupportPrivacy.KEY).execute() }
        privacy.refresh()
    }

    private fun get(path: String, htmx: Boolean = false): String {
        val headers = HttpHeaders().apply { if (htmx) set("HX-Request", "true") }
        return restTemplate.exchange(path, HttpMethod.GET, HttpEntity<Void>(headers), String::class.java).let {
            assertThat(it.statusCode).isEqualTo(HttpStatus.OK)
            it.body!!
        }
    }

    private fun post(path: String, form: Map<String, String>): String {
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
    fun `by default tenants are confidential, each known by its reference, and the catalog mirror is unavailable`() {
        val tenant = createTenant("Privacy Confidential").id

        assertThat(get("/platform/support")).contains("data-testid=\"support-privacy\"").contains("Confidential: Epistola sees each tenant as a reference")
            .contains("Pseudonymised: log messages without their values")
        val support = get("/tenants/${tenant.value}/support")
        val reference = Regex("t-[0-9a-f]{16}").find(support.substringAfter("data-testid=\"support-tenant-reference\""))?.value
        assertThat(reference).`as`("the Support page names the tenant's reference").isEqualTo(references.reference(tenant))

        assertThat(get("/tenants/${tenant.value}/support/mirror")).contains("Confidential").contains("so catalogs are not shared")
        assertThatThrownBy { withMediator { OptInCatalogMirror(tenant, shareByDefault = true, consentVersion = CatalogMirror.CONSENT_VERSION).execute() } }
            .isInstanceOf(CatalogMirrorConfidentialException::class.java)
    }

    @Test
    fun `named, the mirror can be used, and confidential again every tenant's mirror choice is withdrawn`() {
        val tenant = createTenant("Privacy Named").id

        val saved = post("/platform/support/privacy", mapOf("tenantIdentity" to "NAMED", "telemetry" to "FULL"))

        assertThat(saved).contains("Named: Epistola sees each tenant").contains("Everything: log messages and errors as written").contains("Set by ")
        assertThat(get("/tenants/${tenant.value}/support")).doesNotContain("data-testid=\"support-tenant-reference\"")
        withMediator { OptInCatalogMirror(tenant, shareByDefault = true, consentVersion = CatalogMirror.CONSENT_VERSION).execute() }
        assertThat(withMediator { GetCatalogMirrorSettings(tenant).query() }.sharing).isTrue()

        val confidential = post("/platform/support/privacy", mapOf("tenantIdentity" to "CONFIDENTIAL", "telemetry" to "PSEUDONYMISED"))

        assertThat(confidential).contains("the catalog mirror withdraws what it holds")
        assertThat(withMediator { GetCatalogMirrorSettings(tenant).query() }.sharing).`as`("its choice is forgotten").isFalse()
    }

    companion object {
        private val support = FakeSupportServer()

        @JvmStatic
        @DynamicPropertySource
        fun supportService(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.enabled") { "true" }
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
