// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.ReleasePublication
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.FakeExchangeServer
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * What an Exchange outage costs.
 *
 * An unreachable Exchange affects every queued release equally, so it must not be charged to any of
 * them: the retry budget exists to stop one permanently-broken publication retrying forever while
 * holding its archive. With a budget of ten and exponential backoff, counting outages would turn
 * three quarters of an hour of downtime into a pile of terminally failed publications for an
 * administrator to retry by hand.
 */
class ExchangeUnavailableTest : IntegrationTestBase() {

    @Autowired
    private lateinit var worker: CatalogPublicationWorker

    @Autowired
    private lateinit var credentials: ExchangeCredentialService

    @Test
    fun `an outage costs no retries and leaves the connection alone`() {
        val tenant = createTenant("Exchange Outage")
        val catalogKey = CatalogKey.of("outage")

        withMediator {
            enroll(tenant)
            CreateCatalog(tenant.id, catalogKey, "Outage").execute()
            SetCatalogPublicationNamespace(tenant.id, catalogKey, "public-services").execute()
            ReleaseCatalogVersion(tenant.id, catalogKey, "1.0.0", publication = ReleasePublication.PUBLISH).execute()

            // Exchange goes away entirely — not an error response, no response at all.
            exchange.close()

            repeat(3) {
                worker.run()
                forceDue(tenant.id, catalogKey)
            }

            val waiting = publication(tenant.id, catalogKey)
            // Three passes, no retries spent: the outage is not this publication's fault.
            assertThat(waiting.attempts).isZero()
            assertThat(waiting.status.isActive).isTrue()
            assertThat(waiting.failure?.code).isEqualTo(ExchangeFailureCode.EXCHANGE_UNREACHABLE)
            // Nor the connection's — it is not marked broken for being unable to reach a dead host.
            assertThat(credentials.connection(tenant.id)?.status).isEqualTo(ExchangeConnectionStatus.ACTIVE)
            // And the archive is still there, so it publishes itself when Exchange returns.
            assertThat(waiting.archiveRetained).isTrue()
        }
    }

    private fun enroll(tenant: Tenant) {
        SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_PUBLISHING, true).execute()
        StartExchangeConnection(tenant.id, "https://suite.example/oauth/exchange/callback").execute()
        CompleteExchangeConnection(
            tenant.id,
            requireNotNull(exchange.latestState.get()),
            "authorization-code",
            FakeExchangeServer.OAUTH_APPLICATION_ID,
            exchange.baseUrl,
        ).execute()
    }

    private fun publication(tenantKey: TenantKey, catalogKey: CatalogKey) = requireNotNull(GetCatalogPublicationState(tenantKey, catalogKey).query()).publications.single()

    /** The deferral is measured by the database clock, which the test clock cannot move. */
    private fun forceDue(tenantKey: TenantKey, catalogKey: CatalogKey) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate(
            "UPDATE catalog_release_publications SET next_attempt_at = NOW() WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey",
        ).bind("tenantKey", tenantKey).bind("catalogKey", catalogKey).execute()
    }

    @Autowired
    private lateinit var jdbi: org.jdbi.v3.core.Jdbi

    companion object {
        private val exchange = FakeExchangeServer()

        @JvmStatic
        @DynamicPropertySource
        fun exchangeProperties(registry: DynamicPropertyRegistry) {
            registry.add("epistola.exchange.enabled") { "true" }
            registry.add("epistola.exchange.base-url") { exchange.baseUrl }
            registry.add("epistola.exchange.allow-http") { "true" }
            // A dead host must be discovered quickly, not waited out.
            registry.add("epistola.exchange.connect-timeout") { "1s" }
        }

        @JvmStatic
        @AfterAll
        fun stopExchange() = exchange.close()
    }
}
