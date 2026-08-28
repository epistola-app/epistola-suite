// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.ReleasePublication
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.FakeExchangeServer
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Retrying forever is not a resting state: each attempt holds the retained release ZIP, so a
 * publication that keeps failing has to stop and become an administrator's decision. `max-attempts`
 * is set to 1 here so a single failure reaches that boundary.
 */
class CatalogPublicationRetryExhaustionTest : IntegrationTestBase() {

    @Autowired
    private lateinit var worker: CatalogPublicationWorker

    @Test
    fun `exhausted retries become FAILED, keep the archive, and can be requeued by hand`() {
        val tenant = createTenant("Retry Exhaustion")
        val catalogKey = CatalogKey.of("retry-exhaustion")
        exchange.submitResponse = { FakeExchangeServer.Response(500, """{"error":"boom"}""") }

        withMediator {
            SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_PUBLISHING, true).execute()
            StartExchangeConnection(tenant.id, "https://suite.example/oauth/exchange/callback").execute()
            CompleteExchangeConnection(
                tenant.id,
                requireNotNull(exchange.latestState.get()),
                "authorization-code",
                FakeExchangeServer.OAUTH_APPLICATION_ID,
                exchange.baseUrl,
            ).execute()
            CreateCatalog(tenant.id, catalogKey, "Retry exhaustion").execute()
            ReleaseCatalogVersion(tenant.id, catalogKey, "1.0.0", publication = ReleasePublication.PUBLISH).execute()

            worker.run()

            val failed = state(tenant.id, catalogKey).publications.single()
            assertThat(failed.status).isEqualTo(CatalogPublicationStatus.FAILED)
            assertThat(failed.attempts).isEqualTo(1)
            assertThat(failed.archiveRetained).isTrue()

            // A failed attempt is exactly the case the catalog page offers as "Retry publication".
            assertThat(state(tenant.id, catalogKey).canPublishCurrentRelease).isTrue()
            assertThat(state(tenant.id, catalogKey).isRetry).isTrue()

            val firstKey = failed.idempotencyKey
            PublishCurrentCatalogRelease(tenant.id, catalogKey).execute()

            val requeued = state(tenant.id, catalogKey).publications.single()
            assertThat(requeued.id).isEqualTo(failed.id)
            assertThat(requeued.status).isEqualTo(CatalogPublicationStatus.READY)
            assertThat(requeued.attempts).isZero()
            // A new attempt must not be deduplicated against the failed one by Exchange.
            assertThat(requeued.idempotencyKey).isNotEqualTo(firstKey)
        }
    }

    private fun state(tenantKey: app.epistola.suite.common.ids.TenantKey, catalogKey: CatalogKey) = requireNotNull(GetCatalogPublicationState(tenantKey, catalogKey).query())

    companion object {
        private val exchange = FakeExchangeServer()

        @JvmStatic
        @DynamicPropertySource
        fun exchangeProperties(registry: DynamicPropertyRegistry) {
            registry.add("epistola.exchange.enabled") { "true" }
            registry.add("epistola.exchange.base-url") { exchange.baseUrl }
            // The loopback stand-in is plaintext.
            registry.add("epistola.exchange.allow-http") { "true" }
            registry.add("epistola.exchange.max-attempts") { "1" }
        }

        @JvmStatic
        @AfterAll
        fun stopExchange() = exchange.close()
    }
}
