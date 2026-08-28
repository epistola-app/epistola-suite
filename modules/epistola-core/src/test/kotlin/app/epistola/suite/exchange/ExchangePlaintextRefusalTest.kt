// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * The HTTPS requirement, with `allow-http` left at its default.
 *
 * The application secret, refresh token and full catalog archive travel over these URLs, so a
 * plaintext Exchange must be refused rather than merely discouraged — including one advertised by
 * a discovery document, which is not under the operator's control.
 */
class ExchangePlaintextRefusalTest : IntegrationTestBase() {

    @Test
    fun `a plaintext Exchange advertised by discovery is refused`() {
        val tenant = createTenant("exchange-plaintext")

        withMediator {
            SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_PUBLISHING, true).execute()
            assertThatThrownBy {
                StartExchangeConnection(tenant.id, "https://suite.example/oauth/exchange/callback").execute()
            }.hasMessageContaining("must use HTTPS")
        }
    }

    companion object {
        private val exchange = FakeExchangeServer()

        @JvmStatic
        @DynamicPropertySource
        fun exchangeProperties(registry: DynamicPropertyRegistry) {
            registry.add("epistola.exchange.enabled") { "true" }
            registry.add("epistola.exchange.discovery-url") { "${exchange.baseUrl}/.well-known/epistola/exchange.json" }
            // allow-http deliberately left at its default of false.
        }

        @JvmStatic
        @AfterAll
        fun stopExchange() = exchange.close()
    }
}
