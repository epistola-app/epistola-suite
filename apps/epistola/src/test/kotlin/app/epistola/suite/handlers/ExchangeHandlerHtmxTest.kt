// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.util.LinkedMultiValueMap

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
        enablePublishing(tenant)

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

    /** Connecting requires the tenant feature as well as the deployment gate. */
    private fun enablePublishing(tenant: Tenant) = withMediator {
        SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_PUBLISHING, true).execute()
    }

    @Test
    fun `a malformed authorization callback is a bad request, not a server error`() {
        val response = restTemplate.getForEntity(
            "/oauth/exchange/callback?state=unknown-state&code=x&client_id=not-a-uuid&iss=https://exchange.example",
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `a rejected setup action is shown on the settings page, not as an error page`() {
        val tenant = createTenant("Exchange Bad Namespace")
        enablePublishing(tenant)

        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }
        val body = LinkedMultiValueMap<String, String>().apply { add("namespace", "not-granted") }
        val response = restTemplate.postForEntity(
            "/tenants/${tenant.id.value}/exchange/namespace",
            HttpEntity(body, headers),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("not available to this Exchange connection")
        assertThat(response.body).contains("Connect to Exchange")
    }
}
