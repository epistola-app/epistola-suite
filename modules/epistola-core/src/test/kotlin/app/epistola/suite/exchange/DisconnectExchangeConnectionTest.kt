// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** Enrollment and its two exits: a guided recovery, and disconnecting. */
class DisconnectExchangeConnectionTest : IntegrationTestBase() {

    @Autowired
    private lateinit var credentials: ExchangeCredentialService

    @BeforeEach
    fun resetExchange() = exchange.reset()

    @Test
    fun `rejected application credentials become a guided recovery state`() {
        val tenant = createTenant("exchange-credential-recovery")
        exchange.tokenResponse = { FakeExchangeServer.Response(401, """{"error":"invalid_client"}""") }

        withMediator {
            StartExchangeConnection(tenant.id, CALLBACK).execute()

            val connection = CompleteExchangeConnection(
                tenant.id,
                requireNotNull(exchange.latestState.get()),
                "single-use-authorization-code",
                FakeExchangeServer.OAUTH_APPLICATION_ID,
                exchange.baseUrl,
            ).execute()

            assertThat(connection.status).isEqualTo(ExchangeConnectionStatus.REAUTHORIZATION_REQUIRED)
            assertThat(connection.lastError).contains("Recover application credentials")
            assertThat(FindExchangeAuthorizationTenant(requireNotNull(exchange.latestState.get())).query()).isNull()
        }
    }

    @Test
    fun `completing authorization stores the endpoints the issuer advertised`() {
        val tenant = createTenant("exchange-endpoints")

        withMediator {
            StartExchangeConnection(tenant.id, CALLBACK).execute()
            val connection = CompleteExchangeConnection(
                tenant.id,
                requireNotNull(exchange.latestState.get()),
                "authorization-code",
                FakeExchangeServer.OAUTH_APPLICATION_ID,
                exchange.baseUrl,
            ).execute()

            assertThat(connection.status).isEqualTo(ExchangeConnectionStatus.ACTIVE)
            assertThat(connection.tokenEndpoint).isEqualTo("${exchange.baseUrl}/oauth/token")
            assertThat(connection.endpoints.tokenEndpoint).isEqualTo(connection.tokenEndpoint)
            assertThat(connection.namespaces).containsExactly("public-services")
            // A single granted namespace needs no choice, so it becomes the default.
            assertThat(connection.defaultNamespace).isEqualTo("public-services")
        }
    }

    @Test
    fun `a stale authorization state is refused`() {
        val tenant = createTenant("exchange-stale-state")

        withMediator {
            StartExchangeConnection(tenant.id, CALLBACK).execute()
            assertThatThrownBy {
                CompleteExchangeConnection(
                    tenant.id,
                    "not-the-state-we-issued",
                    "authorization-code",
                    FakeExchangeServer.OAUTH_APPLICATION_ID,
                    exchange.baseUrl,
                ).execute()
            }.isInstanceOfSatisfying(ValidationException::class.java) {
                assertThat(it.code).isEqualTo(ValidationCode.EXCHANGE_AUTHORIZATION_INVALID)
            }
        }
    }

    @Test
    fun `disconnect removes the connection and pending authorization created by production commands`() {
        val tenant = createTenant("exchange-disconnect")

        withMediator {
            StartExchangeConnection(tenant.id, CALLBACK).execute()

            assertThat(GetExchangeConnection(tenant.id).query()).isNotNull
            assertThat(FindExchangeAuthorizationTenant(requireNotNull(exchange.latestState.get())).query()).isEqualTo(tenant.id)

            DisconnectExchangeConnection(tenant.id).execute()

            assertThat(GetExchangeConnection(tenant.id).query()).isNull()
            assertThat(FindExchangeAuthorizationTenant(requireNotNull(exchange.latestState.get())).query()).isNull()
        }
    }

    @Test
    fun `a broken connection can only be dropped with the explicit local-only action`() {
        val tenant = createTenant("exchange-forget-local")

        withMediator {
            StartExchangeConnection(tenant.id, CALLBACK).execute()
            CompleteExchangeConnection(
                tenant.id,
                requireNotNull(exchange.latestState.get()),
                "authorization-code",
                FakeExchangeServer.OAUTH_APPLICATION_ID,
                exchange.baseUrl,
            ).execute()
            credentials.markConnection(tenant.id, ExchangeConnectionStatus.BLOCKED, "Exchange revoked us")

            assertThatThrownBy { DisconnectExchangeConnection(tenant.id).execute() }
                .isInstanceOfSatisfying(ValidationException::class.java) {
                    assertThat(it.code).isEqualTo(ValidationCode.EXCHANGE_CONNECTION_NOT_ACTIVE)
                }

            DisconnectExchangeConnection(tenant.id, forgetLocally = true).execute()
            assertThat(GetExchangeConnection(tenant.id).query()).isNull()
        }
    }

    companion object {
        private const val CALLBACK = "https://suite.example/oauth/exchange/callback"
        private val exchange = FakeExchangeServer()

        @JvmStatic
        @DynamicPropertySource
        fun exchangeProperties(registry: DynamicPropertyRegistry) {
            registry.add("epistola.exchange.enabled") { "true" }
            registry.add("epistola.exchange.base-url") { exchange.baseUrl }
            // The loopback stand-in is plaintext.
            registry.add("epistola.exchange.allow-http") { "true" }
        }

        @JvmStatic
        @AfterAll
        fun stopExchange() = exchange.close()
    }
}
