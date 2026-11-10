// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.mediator.execute
import app.epistola.suite.support.connection.DefaultSupportConnection
import app.epistola.suite.support.connection.EnsureConfiguredSupportConnection
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

/**
 * `/platform/support` with `epistola.support.auto-register` on: the check registers on its own, and
 * registers again at once when the claim code expired unlinked, which the page then says.
 */
class SupportAutoRegisterPageHtmxTest : BaseIntegrationTest() {
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

    private fun check(): String {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            set("HX-Request", "true")
        }
        return restTemplate.postForEntity("/platform/support/connections/${epistola()}/check", HttpEntity(LinkedMultiValueMap<String, String>(), headers), String::class.java).let {
            assertThat(it.statusCode).isEqualTo(HttpStatus.OK)
            it.body!!
        }
    }

    @Test
    fun `the check registers on its own, and Register is still offered before it does`() {
        val before = restTemplate.getForEntity("/platform/support/connections/${epistola()}", String::class.java).body!!
        assertThat(before).contains("Not registered").contains("data-testid=\"support-register\"")

        val fragment = check()

        assertThat(fragment).contains("Waiting for activation").contains(">${support.latestRegistration.claimCode}</code>")
        assertThat(fragment).doesNotContain("data-testid=\"support-replaced-expired\"")
    }

    @Test
    fun `an expired claim code is replaced at once, and the page says the previous one expired`() {
        // Issued already expired: invalid_client counts as "gone" only once the claim code expired.
        support.claimCodeLifetime = java.time.Duration.ofMinutes(-1)
        check()
        support.claimCodeLifetime = java.time.Duration.ofDays(7)
        val first = support.latestRegistration
        support.expire(first.clientId)

        val fragment = check()

        val second = support.latestRegistration
        assertThat(second.clientId).isNotEqualTo(first.clientId)
        assertThat(fragment).contains("Waiting for activation").contains(">${second.claimCode}</code>")
        assertThat(fragment.substringAfter("data-testid=\"support-replaced-expired\"").substringBefore("</div>"))
            .contains("The previous registration expired before Epistola activated this installation")
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
            registry.add("epistola.support.auto-register") { "true" }
            registry.add("epistola.support.endpoint") { support.baseUrl }
            registry.add("epistola.support.allow-http") { "true" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = support.close()
    }
}
