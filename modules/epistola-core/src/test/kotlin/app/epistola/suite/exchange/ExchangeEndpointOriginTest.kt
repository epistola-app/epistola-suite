// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.FakeExchangeServer
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Where a discovered OAuth endpoint is allowed to point.
 *
 * The issuer is what an operator configured and therefore chose to trust. The endpoints are
 * whatever the document served at that address happens to say — and the token endpoint is where
 * the client secret and the refresh token are sent. A discovery response that names another host
 * is asking for the credentials to be delivered somewhere else, and the OAuth metadata spec is
 * happy to let it: nothing but this check stands in the way.
 */
class ExchangeEndpointOriginTest : IntegrationTestBase() {
    @BeforeEach
    fun resetExchange() = exchange.reset()

    @Test
    fun `an endpoint on another origin is refused rather than trusted`() {
        val tenant = createTenant("exchange-origin")
        exchange.oauthEndpointBaseUrl = "http://credential-thief.test:4000"

        withMediator {
            SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_PUBLISHING, true).execute()
            assertThatThrownBy {
                StartExchangeConnection(tenant.id, "https://suite.example/oauth/exchange/callback").execute()
            }.isInstanceOf(ExchangeProtocolException::class.java)
                .hasMessageContaining("is not on the issuer's origin")
        }
    }

    @Test
    fun `the issuer's own endpoints are accepted`() {
        val tenant = createTenant("exchange-origin-ok")

        withMediator {
            SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_PUBLISHING, true).execute()
            assertThatCode {
                StartExchangeConnection(tenant.id, "https://suite.example/oauth/exchange/callback").execute()
            }.doesNotThrowAnyException()
        }
    }

    companion object {
        private val exchange = FakeExchangeServer()

        @JvmStatic
        @DynamicPropertySource
        fun exchangeProperties(registry: DynamicPropertyRegistry) {
            registry.add("epistola.exchange.enabled") { "true" }
            registry.add("epistola.exchange.discovery-url") { "${exchange.baseUrl}/.well-known/epistola/exchange.json" }
            // The fake serves plaintext on loopback, so HTTPS is relaxed; the origin rule under test
            // is separate from it and applies either way.
            registry.add("epistola.exchange.allow-http") { "true" }
        }

        @JvmStatic
        @AfterAll
        fun stopExchange() = exchange.close()
    }
}
