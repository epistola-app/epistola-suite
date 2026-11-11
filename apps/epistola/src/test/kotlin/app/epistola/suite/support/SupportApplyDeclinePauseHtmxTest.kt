// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.suite.mediator.execute
import app.epistola.suite.support.connection.DefaultSupportConnection
import app.epistola.suite.support.connection.EnsureConfiguredSupportConnection
import app.epistola.suite.support.connection.SupportConnectionStore
import app.epistola.suite.support.connection.SupportScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

/**
 * Registering is applying for support: the administrator checks who Epistola contacts, and the page
 * then says Epistola will contact that address. Epistola may decline the installation, or pause one
 * of its services for a while, and the page says so with the reason.
 */
class SupportApplyDeclinePauseHtmxTest : ManualSupportTestBase() {
    /** The connection epistola.support.* configures, as startup stores it. */
    private fun epistola() = withMediator { EnsureConfiguredSupportConnection().execute() }

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

    private fun page(): String = restTemplate.getForEntity("/platform/support/connections/${epistola()}", String::class.java).body!!

    @Test
    fun `the administrator confirms who Epistola contacts, and the waiting page names that address`() {
        assertThat(page()).contains("data-testid=\"support-register-form\"").contains("name=\"adminEmail\"")

        val invalid = post("/platform/support/connections/${epistola()}/register", mapOf("companyName" to "Gemeente Test", "adminEmail" to "not an address"))
        assertThat(invalid).contains("Not registered:")

        val waiting = post("/platform/support/connections/${epistola()}/register", mapOf("companyName" to "Gemeente Corrected", "adminEmail" to "support@corrected.example"))

        assertThat(waiting).contains("Waiting for Epistola.").contains("support@corrected.example")
        val sent = support.registrationRequests.last()
        assertThat(sent.path("epistola_company_name").stringValue()).isEqualTo("Gemeente Corrected")
        assertThat(sent.path("epistola_admin_email").stringValue()).isEqualTo("support@corrected.example")
    }

    @Test
    fun `a declined installation says so and offers to apply again, without registering by itself`() {
        post("/platform/support/connections/${epistola()}/register", mapOf("companyName" to "Gemeente Test", "adminEmail" to "beheer@test.example"))
        support.decline()
        val registrations = support.registrations.size

        val declined = post("/platform/support/connections/${epistola()}/check")
        connection.checkNow()

        assertThat(declined).contains("Declined").contains("data-testid=\"support-declined\"").contains("data-testid=\"support-register-again\"")
        assertThat(support.registrations).`as`("declined is not taken for expired: nothing registers again").hasSize(registrations)
        assertThat(connection.hasScope(SupportScope.REQUESTS)).isFalse()
    }

    @Test
    fun `a paused service shows as paused by Epistola with the reason, and is not used`() {
        post("/platform/support/connections/${epistola()}/register", mapOf("companyName" to "Gemeente Test", "adminEmail" to "beheer@test.example"))
        support.activate(scopes = setOf("requests", "mirror"))
        support.pause(setOf("requests"), "Planned maintenance of the feedback inbox")

        val paused = post("/platform/support/connections/${epistola()}/check")
        connection.refreshTokenIfDue()

        assertThat(paused).contains("data-testid=\"support-paused\"").contains("Planned maintenance of the feedback inbox").contains("until Epistola resumes it")
        assertThat(paused.substringAfter("data-scope=\"requests\"").substringBefore("</tr>")).contains("Paused by Epistola").contains("Granted")
        assertThat(connection.hasScope(SupportScope.REQUESTS)).isFalse()
        assertThat(connection.hasScope(SupportScope.MIRROR)).isTrue()

        support.resume()
        post("/platform/support/connections/${epistola()}/check")
        assertThat(connection.hasScope(SupportScope.REQUESTS)).isTrue()
    }
}
