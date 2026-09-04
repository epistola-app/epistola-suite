// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus

/**
 * What an administrator sees when Exchange answers with something Suite cannot use.
 *
 * This is not hypothetical: pointing Suite at an Exchange started on a different profile makes its
 * OAuth metadata advertise a different issuer than discovery, and that anti-spoofing check fires
 * before any browser redirect. It is therefore the likeliest first-contact failure, and until this
 * it produced a blank 500 — the worst possible diagnostics for the moment you are trying to get
 * two services talking.
 */
class ExchangeProtocolFailureHandlerTest : ExchangeHandlerTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `an issuer mismatch is explained on the settings page instead of failing the request`() {
        val tenant = createTenant("Exchange Issuer Mismatch")
        withMediator { SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_PUBLISHING, true).execute() }
        exchange.oauthMetadataIssuer = "http://somewhere-else.example"

        val response = restTemplate.postForEntity(
            "/tenants/${tenant.id.value}/exchange/connect",
            null,
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        // The reason, not just "it failed" — naming both sides is what makes it fixable.
        assertThat(response.body).contains("Exchange OAuth issuer mismatch")
        assertThat(response.body).contains("somewhere-else.example")
        // Still the settings page, so the administrator can correct it and retry.
        assertThat(response.body).contains("Tenant connection")
    }
}
