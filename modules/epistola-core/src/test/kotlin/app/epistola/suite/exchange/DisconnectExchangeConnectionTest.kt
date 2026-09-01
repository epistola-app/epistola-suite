// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.ReleasePublication
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.exchange.SetCatalogPublicationNamespace
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.FakeExchangeServer
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
            beginAuthorization(tenant)

            val connection = CompleteExchangeConnection(
                tenant.id,
                requireNotNull(exchange.latestState.get()),
                "single-use-authorization-code",
                FakeExchangeServer.OAUTH_APPLICATION_ID,
                exchange.baseUrl,
            ).execute()

            assertThat(connection.status).isEqualTo(ExchangeConnectionStatus.REAUTHORIZATION_REQUIRED)
            assertThat(connection.failure?.code).isEqualTo(ExchangeFailureCode.APPLICATION_UNKNOWN)
            assertThat(FindExchangeAuthorizationTenant(requireNotNull(exchange.latestState.get())).query()).isNull()
        }
    }

    @Test
    fun `completing authorization stores the endpoints the issuer advertised`() {
        val tenant = createTenant("exchange-endpoints")

        withMediator {
            beginAuthorization(tenant)
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
            beginAuthorization(tenant)
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
    fun `starting a reauthorization leaves a working connection working`() {
        val tenant = createTenant("exchange-reauthorize")

        withMediator {
            enroll(tenant)
            assertThat(credentials.activeConnection(tenant.id)).isNotNull

            // The administrator opens the authorization page and never finishes it.
            beginAuthorization(tenant)

            // Publishing must keep working until a new authorization actually completes.
            val connection = requireNotNull(credentials.connection(tenant.id))
            assertThat(connection.status).isEqualTo(ExchangeConnectionStatus.ACTIVE)
            assertThat(credentials.activeConnection(tenant.id)).isNotNull
            assertThat(connection.accessToken).isNotNull
        }
    }

    @Test
    fun `disconnecting fails queued publications instead of leaving them to spin`() {
        val tenant = createTenant("exchange-disconnect-queue")
        val catalogKey = CatalogKey.of("disconnect-queue")

        withMediator {
            enroll(tenant)
            CreateCatalog(tenant.id, catalogKey, "Disconnect queue").execute()
            SetCatalogPublicationNamespace(tenant.id, catalogKey, "public-services").execute()
            ReleaseCatalogVersion(tenant.id, catalogKey, "1.0.0", publication = ReleasePublication.PUBLISH).execute()
            assertThat(publication(tenant.id, catalogKey).status.isActive).isTrue()

            DisconnectExchangeConnection(tenant.id, forgetLocally = true).execute()

            val abandoned = publication(tenant.id, catalogKey)
            assertThat(abandoned.status).isEqualTo(CatalogPublicationStatus.FAILED)
            assertThat(abandoned.failure?.code).isEqualTo(ExchangeFailureCode.CONNECTION_DISCONNECTED)
            // The archive survives, so reconnecting and retrying is still possible.
            assertThat(abandoned.archiveRetained).isTrue()
        }
    }

    @Test
    fun `disconnect removes the connection and pending authorization created by production commands`() {
        val tenant = createTenant("exchange-disconnect")

        withMediator {
            beginAuthorization(tenant)

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
            beginAuthorization(tenant)
            CompleteExchangeConnection(
                tenant.id,
                requireNotNull(exchange.latestState.get()),
                "authorization-code",
                FakeExchangeServer.OAUTH_APPLICATION_ID,
                exchange.baseUrl,
            ).execute()
            credentials.markConnection(tenant.id, ExchangeConnectionStatus.BLOCKED, ExchangeFailureCode.CONNECTION_REFUSED)

            assertThatThrownBy { DisconnectExchangeConnection(tenant.id).execute() }
                .isInstanceOfSatisfying(ValidationException::class.java) {
                    assertThat(it.code).isEqualTo(ValidationCode.EXCHANGE_CONNECTION_NOT_ACTIVE)
                }

            DisconnectExchangeConnection(tenant.id, forgetLocally = true).execute()
            assertThat(GetExchangeConnection(tenant.id).query()).isNull()
        }
    }

    /** Enrolling requires the tenant feature, so the toggle is part of starting authorization. */
    private fun beginAuthorization(tenant: Tenant) {
        SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_PUBLISHING, true).execute()
        StartExchangeConnection(tenant.id, CALLBACK).execute()
    }

    /**
     * An Exchange that no longer knows the identity this tenant holds - rebuilt, or restored from
     * before the enrollment - used to be a dead end: reauthorizing offered ids Exchange rejected,
     * and the only way out was to work out that "forget locally, then connect" was what the failure
     * meant. Enrolling afresh is the recovery, so it happens without being asked for.
     */
    @Test
    fun `an identity Exchange no longer knows is re-enrolled rather than refused`() {
        val tenant = createTenant("Exchange Forgotten Identity")

        withMediator {
            enroll(tenant)
            val enrolled = requireNotNull(credentials.connection(tenant.id))
            assertThat(enrolled.oauthApplicationId).isNotNull()

            // Exchange has been rebuilt: it rejects the application and connection Suite still holds.
            exchange.rejectCarriedIdentity = true

            StartExchangeConnection(tenant.id, CALLBACK).execute()

            // The stale identity is dropped rather than carried into the new authorization.
            val restarted = requireNotNull(credentials.connection(tenant.id))
            assertThat(restarted.oauthApplicationId).isNull()
            assertThat(restarted.clientSecret).isNull()
            assertThat(restarted.tenantConnectionId).isNull()
        }
    }

    private fun enroll(tenant: Tenant) {
        beginAuthorization(tenant)
        CompleteExchangeConnection(
            tenant.id,
            requireNotNull(exchange.latestState.get()),
            "authorization-code",
            FakeExchangeServer.OAUTH_APPLICATION_ID,
            exchange.baseUrl,
        ).execute()
    }

    private fun publication(tenantKey: TenantKey, catalogKey: CatalogKey) = requireNotNull(GetCatalogPublicationState(tenantKey, catalogKey).query()).publications.single()

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
