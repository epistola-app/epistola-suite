// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.connection.DefaultSupportConnection
import app.epistola.suite.support.connection.EnsureConfiguredSupportConnection
import app.epistola.suite.support.connection.SupportConnectionStore
import app.epistola.suite.support.sharing.SetSharingRestriction
import app.epistola.suite.support.sharing.SharingNotAllowedException
import app.epistola.suite.support.usage.ForgetUsageSharing
import app.epistola.suite.support.usage.GetUsageChoice
import app.epistola.suite.support.usage.SetUsageChoice
import app.epistola.suite.testing.FakeSupportServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
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
 * The tenant's Support page as the one place it sets what it shares with Epistola: a setup wizard
 * while nothing is set up, the installation preset while it follows one, and its own choice, against
 * a stand-in support service. Usage is the section this version has; each shared service adds its own.
 */
@Isolated("The installation preset applies to every tenant in the database")
class SupportSharingSetupHtmxTest : BaseIntegrationTest() {
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
        store.forget()
        connection.invalidate()
        connection.checkNow()
        support.activate(scopes = setOf("usage"))
        connection.checkNow()
    }

    @AfterEach
    fun resetPreset() {
        store.forgetServices()
        withMediator {
            ForgetUsageSharing(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute()
            app.epistola.suite.support.mirror.ForgetCatalogMirrorSharing(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute()
            SetSharingRestriction("usage", null, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute()
            SetSharingRestriction("mirror", null, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute()
        }
    }

    private fun get(path: String, htmx: Boolean = false): String {
        val headers = HttpHeaders().apply { if (htmx) set("HX-Request", "true") }
        return restTemplate.exchange(path, org.springframework.http.HttpMethod.GET, HttpEntity<Void>(headers), String::class.java).let {
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

    private fun setup(tenant: TenantKey, form: Map<String, String>) = post("/tenants/${tenant.value}/support/setup", form)

    private fun usage(tenant: TenantKey) = withMediator { GetUsageChoice(tenant, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }

    @Test
    fun `with nothing set up, the Support page opens the setup wizard`() {
        val tenant = createTenant("Setup Wizard Opens")

        val body = get("/tenants/${tenant.id.value}/support")

        assertThat(body).contains("Set up Epistola support for Setup Wizard Opens")
            .contains("data-testid=\"support-setup\"").contains("data-step=\"0\"")
        assertThat(body).doesNotContain("data-testid=\"support-sharing-summary\"")
    }

    @Test
    fun `the wizard asks the choice with what is sent, and saves nothing before the agreement`() {
        val tenant = createTenant("Setup Wizard Walk").id

        val usageStep = setup(tenant, mapOf("step" to "0", "action" to "next"))
        assertThat(usageStep).contains("data-step=\"1\"").contains("data-testid=\"usage-statement\"")
        assertThat(setup(tenant, mapOf("step" to "1", "action" to "next"))).`as`("a choice is needed")
            .contains("data-testid=\"support-setup-error\"").contains("data-step=\"1\"")

        val review = setup(tenant, mapOf("step" to "1", "action" to "next", "usage.choice" to "SHARE"))
        assertThat(review).contains("data-testid=\"support-setup-review\"").contains("Share usage counts with Epistola")
        val notAgreed = setup(tenant, mapOf("step" to "2", "action" to "finish", "usage.choice" to "SHARE"))
        assertThat(notAgreed).contains("data-testid=\"support-setup-error\"").contains("agree on behalf of")
        assertThat(usage(tenant).share).`as`("nothing saved yet").isNull()

        val done = setup(tenant, mapOf("step" to "2", "action" to "finish", "agree" to "true", "usage.choice" to "SHARE"))

        assertThat(done).contains("Saved. This is what Setup Wizard Walk shares with Epistola now.").contains("data-testid=\"support-sharing-summary\"")
            .contains("Set up for this tenant").contains("data-testid=\"support-sharing-change\"")
        assertThat(usage(tenant).share).isTrue()
    }

    @Test
    fun `with an installation preset, a tenant is set up already and can change it or go back to the preset`() {
        val tenant = createTenant("Setup Preset").id
        post("/platform/support/defaults", mapOf("usage.default" to "SHARE", "agree" to "true"))

        val preset = get("/tenants/${tenant.value}/support")
        assertThat(preset).contains("Epistola support is set up for Setup Preset").contains("Set up by your installation administrator")
            .contains("Change for this tenant").doesNotContain("data-testid=\"support-setup\"")

        val changing = get("/tenants/${tenant.value}/support/setup", htmx = true)
        assertThat(changing).contains("Change what Setup Preset shares").contains("data-testid=\"support-setup-cancel\"")
        val own = setup(tenant, mapOf("step" to "2", "action" to "finish", "agree" to "true", "changing" to "true", "usage.choice" to "NO"))
        assertThat(own).contains("Set up for this tenant").contains("data-testid=\"support-sharing-use-preset\"")
        assertThat(usage(tenant).share).isFalse()

        val back = post("/tenants/${tenant.value}/support/use-preset", emptyMap())

        assertThat(back).contains("This tenant uses the installation").contains("Set up by your installation administrator")
        assertThat(usage(tenant).share).`as`("it follows the preset again").isNull()
    }

    @Test
    fun `the platform page sets the preset for every tenant that has not chosen itself, after agreeing on their behalf`() {
        val own = createTenant("Setup Preset Own").id
        setup(own, mapOf("step" to "2", "action" to "finish", "agree" to "true", "usage.choice" to "NO"))

        val notAgreed = post("/platform/support/defaults", mapOf("usage.default" to "SHARE"))
        assertThat(notAgreed).`as`("sharing is agreed on the tenants' behalf").contains("data-testid=\"support-defaults-error\"")
            .contains("data-testid=\"support-defaults-form\"")

        val panel = post("/platform/support/defaults", mapOf("usage.default" to "SHARE", "agree" to "true"))

        assertThat(panel).contains("data-testid=\"support-defaults-notice\"").contains("Saved.").contains("data-testid=\"support-defaults-summary\"")
        assertThat(usage(own).share).`as`("its own choice wins").isFalse()
        assertThat(get("/platform/support")).contains("data-testid=\"support-defaults-summary\"").contains("Share usage counts with Epistola").contains("Set by ")
    }

    @Test
    fun `required takes usage off the tenant's page, and not allowed withdraws every choice until it is allowed again`() {
        val own = createTenant("Usage Restricted").id
        setup(own, mapOf("step" to "2", "action" to "finish", "agree" to "true", "usage.choice" to "SHARE"))

        val required = post("/platform/support/defaults", mapOf("usage.default" to "REQUIRED", "agree" to "true"))
        assertThat(required).contains("data-testid=\"support-defaults-required\"")
        assertThat(get("/tenants/${own.value}/support")).`as`("a Required service is no tenant's choice").doesNotContain("data-testid=\"usage-statement\"")
            .doesNotContain("Share usage counts with Epistola")

        val notAllowed = post("/platform/support/defaults", mapOf("usage.default" to "NOT_ALLOWED"))
        assertThat(notAllowed).contains("data-testid=\"support-defaults-not-allowed\"")
        assertThat(usage(own).share).`as`("its own choice is forgotten too").isNull()
        assertThatThrownBy { withMediator { SetUsageChoice(own, share = true, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() } }.isInstanceOf(SharingNotAllowedException::class.java)
    }

    @Test
    fun `required with no default leaves a tenant nothing to set up, and no wizard that never ends`() {
        val tenant = createTenant("Usage Required Only").id
        post("/platform/support/defaults", mapOf("usage.default" to "REQUIRED", "agree" to "true"))

        val page = get("/tenants/${tenant.value}/support")

        assertThat(page).doesNotContain("data-testid=\"support-setup\"").contains("data-testid=\"support-sharing-nothing\"")
    }

    @Test
    fun `a service the installation has not selected is not offered to tenants`() {
        val tenant = createTenant("Usage Not Selected").id
        post("/platform/support/connections/${epistola()}/services", mapOf("services" to "telemetry"))

        val page = get("/tenants/${tenant.value}/support")

        assertThat(page).doesNotContain("data-testid=\"support-setup\"").doesNotContain("data-testid=\"usage-statement\"")
            .contains("data-testid=\"support-sharing-nothing\"")
    }

    @Test
    fun `finishing with a choice the installation forbade meanwhile says so in the wizard and saves nothing`() {
        val tenant = createTenant("Usage Forbidden Meanwhile").id
        setup(tenant, mapOf("step" to "1", "action" to "next", "usage.choice" to "SHARE"))
        post("/platform/support/defaults", mapOf("usage.default" to "NOT_ALLOWED"))

        val finished = setup(tenant, mapOf("step" to "2", "action" to "finish", "agree" to "true", "usage.choice" to "SHARE"))

        assertThat(finished).contains("data-testid=\"support-setup-error\"").contains("Not allowed in this installation")
        assertThat(usage(tenant).share).isNull()
    }

    @Test
    fun `only an installation administrator sees or changes the setup for every tenant`() {
        val tenantAdministrator = HttpHeaders().apply {
            set(app.epistola.suite.testing.TEST_TENANT_ROLES_HEADER, "TENANT_ADMINISTRATOR")
            set("HX-Request", "true")
        }

        for (path in listOf("/platform/support/defaults", "/platform/support/defaults/edit")) {
            val response = restTemplate.exchange(path, org.springframework.http.HttpMethod.GET, HttpEntity<Void>(tenantAdministrator), String::class.java)
            assertThat(response.statusCode).`as`(path).isEqualTo(HttpStatus.FORBIDDEN)
        }
    }

    @Test
    fun `while tenants are confidential the preset offers no catalog sharing, and saving one says why`() {
        withMediator { app.epistola.suite.support.connection.SaveSupportServices(setOf("usage", "mirror")).execute() }
        support.activate(scopes = setOf("usage", "mirror"))
        connection.checkNow()
        withMediator { app.epistola.suite.support.privacy.SetSupportPrivacy(app.epistola.suite.support.privacy.TenantIdentity.CONFIDENTIAL, app.epistola.suite.support.privacy.TelemetryDetail.PSEUDONYMISED).execute() }
        try {
            val form = get("/platform/support/defaults/edit", htmx = true)
            assertThat(Regex("<input[^>]*data-testid=\"sharing-default-mirror-all\"[^>]*>").find(form)!!.value).contains("disabled")
            assertThat(form).contains("keeps its tenants confidential to Epistola")

            val refused = post("/platform/support/defaults", mapOf("mirror.default" to "ALL", "usage.default" to "NONE", "agree" to "true"))

            assertThat(refused).contains("data-testid=\"support-defaults-error\"").contains("keeps its tenants confidential to Epistola").contains("data-testid=\"support-defaults-form\"")
            assertThat(withMediator { app.epistola.suite.support.mirror.GetCatalogMirrorDefault(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.isSet).`as`("nothing saved").isFalse()
        } finally {
            withMediator { app.epistola.suite.support.privacy.SetSupportPrivacy(app.epistola.suite.support.privacy.TenantIdentity.NAMED, app.epistola.suite.support.privacy.TelemetryDetail.PSEUDONYMISED).execute() }
        }
    }

    @Test
    fun `with the mirror selected, the wizard asks catalogs then usage, and saves both`() {
        withMediator { app.epistola.suite.support.connection.SaveSupportServices(setOf("usage", "mirror")).execute() }
        support.activate(scopes = setOf("usage", "mirror"))
        connection.checkNow()
        val tenant = createTenant("Setup With Mirror").id

        val catalogs = setup(tenant, mapOf("step" to "0", "action" to "next"))
        assertThat(catalogs).contains("data-step=\"1\"").contains("data-testid=\"mirror-statement\"")
        val usageStep = setup(tenant, mapOf("step" to "1", "action" to "next", "mirror.choice" to "MARKED"))
        assertThat(usageStep).contains("data-step=\"2\"").contains("data-testid=\"usage-statement\"")

        val done = setup(tenant, mapOf("step" to "3", "action" to "finish", "agree" to "true", "mirror.choice" to "MARKED", "usage.choice" to "NO"))

        assertThat(done).contains("data-testid=\"support-sharing-summary\"")
        val mirror = withMediator { app.epistola.suite.support.mirror.GetCatalogMirrorSettings(tenant, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }
        assertThat(mirror.sharing).isTrue()
        assertThat(mirror.consent!!.shareByDefault).isFalse()
        assertThat(usage(tenant).share).isFalse()
    }

    companion object {
        private val support = FakeSupportServer()

        @JvmStatic
        @DynamicPropertySource
        fun supportService(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.enabled") { "true" }
            registry.add("epistola.support.privacy.tenant-identity") { "named" }
            registry.add("epistola.support.services") { "telemetry,usage" }
            registry.add("epistola.support.auto-register") { "true" }
            registry.add("epistola.support.endpoint") { support.baseUrl }
            registry.add("epistola.support.allow-http") { "true" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = support.close()
    }
}
