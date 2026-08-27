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
import org.springframework.test.context.TestPropertySource

/**
 * Server-contract assertions for the Exchange settings page, made against the rendered response
 * rather than the template source, so they keep holding if the markup is reorganized.
 */
@TestPropertySource(properties = ["epistola.exchange.enabled=true"])
class ExchangeHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `an unconnected tenant is offered authorization as a top-level navigation`() {
        val tenant = createTenant("Exchange Page")

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}/exchange", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Connect to Exchange")

        // Authorization leaves our origin, so these forms must not be turned into an HTMX
        // background request — the browser has to follow the redirect itself.
        val connectForms = Regex("""<form\b[^>]*exchange/connect[^>]*>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(requireNotNull(response.body)).map { it.value }.toList()
        assertThat(connectForms).isNotEmpty()
        assertThat(connectForms).allMatch { """hx-boost="false"""" in it }
    }
}
