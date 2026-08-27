// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus

/** With the default-off deployment gate, the page explains itself and offers no way to connect. */
class ExchangeDisabledHandlerTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `the settings page states the deployment gate is off and hides the connect action`() {
        val tenant = createTenant("Exchange Disabled")

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}/exchange", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("epistola.exchange.enabled=true")
        assertThat(response.body).doesNotContain("Connect to Exchange")
    }
}
