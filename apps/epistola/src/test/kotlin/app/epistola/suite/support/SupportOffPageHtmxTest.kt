// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.suite.BaseIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus

/** The support pages with the support connection off, the default: they say so and offer nothing to do. */
class SupportOffPageHtmxTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `the platform page says the connection is off and how to turn it on`() {
        val body = restTemplate.getForEntity("/platform/support", String::class.java).let {
            assertThat(it.statusCode).isEqualTo(HttpStatus.OK)
            it.body!!
        }

        assertThat(body).contains("No support connections yet").contains("epistola.support.enabled=true")
        assertThat(body).doesNotContain("data-testid=\"support-check-now\"").doesNotContain("data-testid=\"support-register-again\"")
    }

    @Test
    fun `the tenant page says the connection is off`() {
        val tenant = createTenant("Support Off Page")

        val body = restTemplate.getForEntity("/tenants/${tenant.id.value}/support", String::class.java).body!!

        assertThat(body).contains("data-testid=\"support-summary\"").contains("does not connect to Epistola support")
    }

    @Test
    fun `the tenant list and the platform menu link the page`() {
        assertThat(restTemplate.getForEntity("/", String::class.java).body).contains("data-testid=\"platform-support-link\"")
        assertThat(restTemplate.getForEntity("/platform/sharing", String::class.java).body).contains("href=\"/platform/support\"")
    }
}
