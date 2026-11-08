// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.support.connection.DefaultSupportConnection
import app.epistola.suite.support.connection.SupportConnectionStore
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
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * `/platform/support` and the tenant support page with the support connection on and
 * `epistola.support.auto-register` off (the default), against a stand-in support service: nothing
 * registered until an administrator presses Register, the claim code and its expiry while pending,
 * an expired code, the services once activated, and the way back from a refusal.
 */
class SupportPlatformPageHtmxTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var store: SupportConnectionStore

    @Autowired
    private lateinit var connection: DefaultSupportConnection

    @BeforeEach
    fun forget() {
        store.forget()
        store.forgetServices()
        connection.invalidate()
    }

    private fun page(): String = restTemplate.getForEntity("/platform/support", String::class.java).let {
        assertThat(it.statusCode).isEqualTo(HttpStatus.OK)
        it.body!!
    }

    private fun post(path: String, form: Map<String, String> = emptyMap()): String = post(path, form.toList())

    private fun post(path: String, form: List<Pair<String, String>>): String {
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
    fun `an unregistered installation says what registering sends and offers Register, and a check does not register`() {
        val before = support.registrationRequests.size
        val body = page()

        assertThat(body).contains("Epistola support").contains("Not registered").contains("data-testid=\"support-check-now\"")
        assertThat(body).contains("data-testid=\"support-register\"").contains("data-testid=\"support-register-explained\"")
        assertThat(body).doesNotContain("data-testid=\"support-register-again\"")
        assertThat(body).contains(support.baseUrl)

        val checked = post("/platform/support/check")
        assertThat(checked).contains("Not registered").contains("data-testid=\"support-register\"").doesNotContain("data-testid=\"support-claim-code\"")
        assertThat(support.registrationRequests).hasSize(before)
    }

    @Test
    fun `register applies for support and says Epistola will contact the address, with the reference and its expiry`() {
        val fragment = post("/platform/support/register", mapOf("companyName" to "Gemeente Test", "adminEmail" to "beheer@test.example"))

        val registration = support.latestRegistration
        val claimCode = registration.claimCode
        assertThat(fragment).contains("id=\"support-connection\"").doesNotContain("<html")
        assertThat(fragment).contains("Waiting for activation")
        assertThat(fragment).contains("Waiting for Epistola.").contains("Epistola will contact you at").contains("beheer@test.example")
        assertThat(fragment).contains(">$claimCode</code>")
        assertThat(fragment.substringAfter("data-testid=\"support-claim-code-expires\"").substringBefore("</p>"))
            .contains(utc(registration.claimCodeExpiresAt))
        assertThat(fragment).doesNotContain("data-testid=\"support-register\"")
        // Registering again while pending invalidates the code, so it asks first.
        assertThat(fragment).contains("data-testid=\"support-register-again\"").contains("data-confirm-submit")
        // The support service keeps the earlier registration, so its code still finds it: the confirmation does not claim otherwise.
        assertThat(fragment).doesNotContain("The current code stops working")
            .contains("mention the new reference")
        assertThat(page()).contains(">$claimCode</code>")
    }

    @Test
    fun `a claim code that expired unlinked says when, and Register gets a new one`() {
        // Issued already expired: invalid_client counts as "gone" only once the claim code expired.
        support.claimCodeLifetime = java.time.Duration.ofMinutes(-1)
        post("/platform/support/register")
        support.claimCodeLifetime = java.time.Duration.ofDays(7)
        val first = support.latestRegistration
        support.expire(first.clientId)

        val expired = post("/platform/support/check")

        assertThat(expired).contains("Claim code expired").contains("data-testid=\"support-register\"")
        assertThat(expired.substringAfter("data-testid=\"support-expired\"").substringBefore("</p>"))
            .contains("The claim code expired on").contains(utc(first.claimCodeExpiresAt))
        assertThat(expired).doesNotContain("data-testid=\"support-claim-code\"")
        // The button says Register, so the sentence does too.
        assertThat(expired.substringAfter("data-testid=\"support-expired\"").substringBefore("</p>"))
            .contains("Register to apply again.").doesNotContain("Register again")

        val again = post("/platform/support/register")

        val second = support.latestRegistration
        assertThat(second.clientId).isNotEqualTo(first.clientId)
        assertThat(again).contains("Waiting for activation").contains(">${second.claimCode}</code>")
    }

    @Test
    fun `an activated installation shows its granted services`() {
        post("/platform/support/register")
        support.activate(scopes = setOf("requests"))

        val fragment = post("/platform/support/check")

        assertThat(fragment).contains("Connected").contains("Epistola activated this installation.")
        val services = fragment.substringAfter("data-testid=\"support-services\"").substringBefore("</table>")
        assertThat(services.substringAfter("data-scope=\"requests\"").substringBefore("</tr>")).contains("Granted")
        assertThat(services.substringAfter("data-scope=\"telemetry\"").substringBefore("</tr>")).contains("Not granted")
        assertThat(fragment).doesNotContain("data-testid=\"support-register-again\"")
        assertThat(fragment).doesNotContain("data-testid=\"support-claim-code\"")
    }

    @Test
    fun `an administrator selects the services, each shows selected, granted and in use, and tenant pages say what is not selected`() {
        post("/platform/support/register")
        support.activate(scopes = setOf("requests", "mirror"))
        val before = post("/platform/support/check")
        assertThat(before.substringAfter("data-testid=\"support-services-source\"").substringBefore("</p>")).contains("epistola.support.services")

        val saved = post("/platform/support/services", listOf("services" to "requests", "services" to "telemetry"))

        assertThat(saved).contains("id=\"support-connection\"").contains("Saved. Selected: requests, telemetry.")
        val services = saved.substringAfter("data-testid=\"support-services\"").substringBefore("</table>")
        fun row(name: String) = services.substringAfter("data-scope=\"$name\"").substringBefore("</tr>")
        assertThat(row("requests")).contains("checked").contains("Granted").contains(">In use<")
        assertThat(row("telemetry")).contains("checked").contains("Not granted").contains("Not in use")
        assertThat(row("mirror")).doesNotContain("checked").contains("Granted").contains("Not in use")
        assertThat(saved.substringAfter("data-testid=\"support-services-source\"").substringBefore("</p>")).contains("Saved").contains("no longer applies")
        assertThat(connection.hasScope(app.epistola.suite.support.connection.SupportScope.MIRROR)).isFalse()

        val tenant = createTenant("Support Not Selected")
        val overview = restTemplate.getForEntity("/tenants/${tenant.id.value}/support", String::class.java).body!!
        assertThat(overview.substringAfter("data-testid=\"support-summary-services\"").substringBefore("</table>"))
            .contains("Not selected by the installation administrator")
    }

    @Test
    fun `saving no services at all is a choice too`() {
        val saved = post("/platform/support/services", emptyList())

        assertThat(saved).contains("Saved. This installation uses no support service.")
        assertThat(connection.selectedServices().services).isEmpty()
        assertThat(connection.selectedServices().fromConfiguration).isFalse()
    }

    @Test
    fun `a refused installation says why and registers again with a new claim code`() {
        post("/platform/support/register")
        val first = support.latestRegistration
        support.activate(first.clientId)
        post("/platform/support/check")
        support.revoke(first.clientId)

        val refused = post("/platform/support/check")
        assertThat(refused).contains("Refused").contains("invalid_client").contains("data-testid=\"support-register-again\"")

        val again = post("/platform/support/register-again", mapOf("confirmed" to "true"))

        val second = support.latestRegistration
        assertThat(second.clientId).isNotEqualTo(first.clientId)
        assertThat(again).contains("Registered again. Epistola will contact you to activate this installation.").contains(">${second.claimCode}</code>")
    }

    @Test
    fun `a node whose clock is off says by how much, and the installation stays connected`() {
        post("/platform/support/register")
        support.activate(support.latestRegistration.clientId)
        post("/platform/support/check")
        // The fake checks assertion times against the real clock, the one its Date header states: this
        // node's clock is set from it to put the two a known distance apart. The check runs on this
        // thread, which the test clock governs; request threads keep the real one.
        support.checksAssertionTimes = true
        testClock.set(Instant.now().plus(Duration.ofMinutes(10)))
        try {
            connection.checkNow()

            val body = page()
            assertThat(body).contains("Connected").contains("about 10 minutes ahead of the support service").contains("NTP")
            assertThat(body).doesNotContain("data-testid=\"support-register-again\"")
            // Another node's page: not its own failure, but the skewed node is named all the same.
            connection.resetBackoff()
            assertThat(page()).contains("data-testid=\"support-node-warning\"").contains("about 10 minutes ahead of the support service")
        } finally {
            support.checksAssertionTimes = false
            connection.resetBackoff()
        }
    }

    @Test
    fun `the tenant page says the installation is pending and lists the tenant's support settings, without the claim code`() {
        post("/platform/support/register")
        val tenant = createTenant("Support Tenant Page")

        val body = restTemplate.getForEntity("/tenants/${tenant.id.value}/support", String::class.java).let {
            assertThat(it.statusCode).isEqualTo(HttpStatus.OK)
            it.body!!
        }

        assertThat(body).contains("data-testid=\"support-summary\"").contains("Pending")
        assertThat(body).doesNotContain(support.latestRegistration.claimCode)
        // Feedback contributes its item to the Support group; the page lists it, not Overview itself.
        assertThat(body).contains("data-testid=\"support-settings-feedback\"").doesNotContain("data-testid=\"support-settings-overview\"")
        // With the connection on, tenant administrators get the Overview item.
        assertThat(body).contains("nav-dropdown-support").contains("href=\"/tenants/${tenant.id.value}/support\"")
    }

    private fun utc(instant: Instant): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC).format(instant) + " UTC"

    companion object {
        private val support = FakeSupportServer()

        @JvmStatic
        @DynamicPropertySource
        fun supportService(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.enabled") { "true" }
            registry.add("epistola.support.privacy.tenant-identity") { "named" }
            // Every service selected, as an administrator would; the selection itself is tested in SupportServiceSelectionIntegrationTest.
            registry.add("epistola.support.services") { "telemetry,requests,mirror" }
            registry.add("epistola.support.endpoint") { support.baseUrl }
            registry.add("epistola.support.allow-http") { "true" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = support.close()
    }
}
