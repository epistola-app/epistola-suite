// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Enrollment through the **public discovery document**, which is the path a real deployment takes.
 *
 * Every other test configures `base-url`, the local-development escape hatch — which is exactly how
 * a production-only defect survived: the escape hatch bound to an empty string, discovery was
 * skipped, and no test noticed because no test used discovery. This class deliberately leaves
 * `base-url` unset.
 *
 * The document served here is the shape epistola.app actually publishes
 * (`{"version":1,"issuer":…,"baseUrl":…}`), so the parser is checked against the real contract
 * rather than against our own reading of it.
 */
class ExchangeDiscoveryIntegrationTest : IntegrationTestBase() {

    @BeforeEach
    fun resetExchange() = exchange.reset()

    @Test
    fun `enrollment resolves endpoints through the discovery document`() {
        val tenant = createTenant("exchange-discovery")

        withMediator {
            SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_PUBLISHING, true).execute()
            StartExchangeConnection(tenant.id, CALLBACK).execute()
            val connection = CompleteExchangeConnection(
                tenant.id,
                requireNotNull(exchange.latestState.get()),
                "authorization-code",
                FakeExchangeServer.OAUTH_APPLICATION_ID,
                exchange.baseUrl,
            ).execute()

            // Discovery → OAuth metadata → persisted endpoints: none of it came from configuration.
            assertThat(connection.status).isEqualTo(ExchangeConnectionStatus.ACTIVE)
            assertThat(connection.issuer).isEqualTo(exchange.baseUrl)
            assertThat(connection.baseUrl).isEqualTo(exchange.baseUrl)
            assertThat(connection.authorizationRequestEndpoint).isEqualTo("${exchange.baseUrl}/oauth/authorization-requests")
            assertThat(connection.tokenEndpoint).isEqualTo("${exchange.baseUrl}/oauth/token")
        }
    }

    @Test
    fun `a discovery document from a future version is refused`() {
        val tenant = createTenant("exchange-discovery-version")
        exchange.discoveryResponse = {
            FakeExchangeServer.Response(200, """{"version":2,"issuer":"${exchange.baseUrl}","baseUrl":"${exchange.baseUrl}"}""")
        }

        withMediator {
            SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_PUBLISHING, true).execute()
            assertThatThrownBy { StartExchangeConnection(tenant.id, CALLBACK).execute() }
                .hasMessageContaining("Unsupported Exchange discovery version")
        }
    }

    @Test
    fun `OAuth metadata that disagrees with the discovered issuer is refused`() {
        val tenant = createTenant("exchange-discovery-issuer")
        exchange.oauthMetadataIssuer = "https://somewhere-else.example"

        withMediator {
            SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_PUBLISHING, true).execute()
            assertThatThrownBy { StartExchangeConnection(tenant.id, CALLBACK).execute() }
                .hasMessageContaining("Exchange OAuth issuer mismatch")
        }
    }

    companion object {
        private const val CALLBACK = "https://suite.example/oauth/exchange/callback"
        private val exchange = FakeExchangeServer()

        @JvmStatic
        @DynamicPropertySource
        fun exchangeProperties(registry: DynamicPropertyRegistry) {
            registry.add("epistola.exchange.enabled") { "true" }
            // Deliberately no base-url: this is the discovery path.
            registry.add("epistola.exchange.discovery-url") { "${exchange.baseUrl}/.well-known/epistola/exchange.json" }
            registry.add("epistola.exchange.allow-http") { "true" }
        }

        @JvmStatic
        @AfterAll
        fun stopExchange() = exchange.close()
    }
}
