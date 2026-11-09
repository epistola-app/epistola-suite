// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.support.connection.TokenSupportConnectionCheckScheduler
import app.epistola.suite.testing.FakeSupportServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Platform → Epistola support with a partner connection from the configuration (ADR 0053 step 3) and
 * no connection to Epistola support: an installation can connect to its partner alone.
 */
class SupportPartnerConnectionPageHtmxTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var checks: TokenSupportConnectionCheckScheduler

    @Test
    fun `the platform page lists the partner connection and what it grants, also with Epistola support off`() {
        partner.issuePartnerToken(TOKEN, setOf("requests"))
        checks.checkAll()

        val body = restTemplate.getForEntity("/platform/support", String::class.java).let {
            assertThat(it.statusCode).isEqualTo(HttpStatus.OK)
            it.body!!
        }

        assertThat(body).contains("does not connect to Epistola support")
        assertThat(body).contains("data-testid=\"support-connections\"").contains("Acme Partner").contains(partner.baseUrl.trimEnd('/'))
            .contains("Connected")
    }

    companion object {
        private const val TOKEN = "partner-page-token"

        private val partner = FakeSupportServer()

        @JvmStatic
        @DynamicPropertySource
        fun partnerConnection(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.allow-http") { "true" }
            registry.add("epistola.support.connections[0].name") { "Acme Partner" }
            registry.add("epistola.support.connections[0].endpoint") { partner.baseUrl }
            registry.add("epistola.support.connections[0].token") { TOKEN }
            registry.add("epistola.support.connections[0].services") { "requests" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = partner.close()
    }
}
