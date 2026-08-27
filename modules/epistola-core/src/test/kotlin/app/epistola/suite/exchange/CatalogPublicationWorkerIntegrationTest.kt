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
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * The worker half of publication, driven against [FakeExchangeServer].
 *
 * These are the paths that only exist once a network is involved: following a submission to a
 * terminal decision, releasing the retained archive exactly then, and distinguishing "not
 * actionable yet" (which must not burn a retry) from "Exchange refused us" (which must mark the
 * connection).
 */
class CatalogPublicationWorkerIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var worker: CatalogPublicationWorker

    @Autowired
    private lateinit var credentials: ExchangeCredentialService

    @Autowired
    private lateinit var jdbi: Jdbi

    @BeforeEach
    fun resetExchange() = exchange.reset()

    @Test
    fun `a queued release is submitted, followed, and its archive released on acceptance`() {
        val tenant = createTenant("Worker Accepts")
        val catalogKey = CatalogKey.of("worker-accepts")

        withMediator {
            enroll(tenant)
            releaseWithPublication(tenant, catalogKey)

            worker.run()
            val submitted = publication(tenant.id, catalogKey)
            assertThat(submitted.status).isEqualTo(CatalogPublicationStatus.SUBMITTED)
            assertThat(submitted.remotePublicationId).isEqualTo(exchange.remotePublicationId)
            assertThat(submitted.archiveRetained).isTrue()
            assertThat(exchange.submittedNamespaces).containsExactly("public-services")
            assertThat(exchange.submittedBytes).isGreaterThan(0)

            // Exchange has not decided yet, so the row is polled rather than resubmitted.
            forceDue(submitted.id)
            exchange.statusResponse = { FakeExchangeServer.Response(200, exchange.publicationBody(exchange.remotePublicationId, "ACCEPTED")) }
            worker.run()

            val accepted = publication(tenant.id, catalogKey)
            assertThat(accepted.status).isEqualTo(CatalogPublicationStatus.ACCEPTED)
            assertThat(accepted.archiveRetained).isFalse()
            assertThat(accepted.attempts).isZero()
            assertThat(exchange.submittedIdempotencyKeys).hasSize(1)
        }
    }

    @Test
    fun `a rejection is terminal and also releases the archive`() {
        val tenant = createTenant("Worker Rejects")
        val catalogKey = CatalogKey.of("worker-rejects")
        exchange.submitResponse = {
            FakeExchangeServer.Response(200, exchange.publicationBody(exchange.remotePublicationId, "REJECTED", "SCAN_FAILED", "Unsafe asset"))
        }

        withMediator {
            enroll(tenant)
            releaseWithPublication(tenant, catalogKey)
            worker.run()

            val rejected = publication(tenant.id, catalogKey)
            assertThat(rejected.status).isEqualTo(CatalogPublicationStatus.REJECTED)
            assertThat(rejected.archiveRetained).isFalse()
            assertThat(rejected.lastError).isEqualTo("SCAN_FAILED: Unsafe asset")
        }
    }

    @Test
    fun `an unenrolled tenant waits for setup instead of consuming retries`() {
        val tenant = createTenant("Worker Waits")
        val catalogKey = CatalogKey.of("worker-waits")

        withMediator {
            SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_PUBLISHING, true).execute()
            releaseWithPublication(tenant, catalogKey)

            worker.run()

            val waiting = publication(tenant.id, catalogKey)
            assertThat(waiting.status).isEqualTo(CatalogPublicationStatus.WAITING_SETUP)
            assertThat(waiting.attempts).isZero()
            assertThat(waiting.archiveRetained).isTrue()
            assertThat(exchange.submittedIdempotencyKeys).isEmpty()
        }
    }

    @Test
    fun `turning the tenant feature off pauses its queue without failing it`() {
        val tenant = createTenant("Worker Paused")
        val catalogKey = CatalogKey.of("worker-paused")

        withMediator {
            enroll(tenant)
            releaseWithPublication(tenant, catalogKey)
            SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_PUBLISHING, false).execute()

            worker.run()

            val paused = publication(tenant.id, catalogKey)
            assertThat(paused.status).isEqualTo(CatalogPublicationStatus.READY)
            assertThat(paused.attempts).isZero()
            assertThat(exchange.submittedIdempotencyKeys).isEmpty()
        }
    }

    @Test
    fun `a forbidden submission blocks the connection and schedules a retry`() {
        val tenant = createTenant("Worker Forbidden")
        val catalogKey = CatalogKey.of("worker-forbidden")
        exchange.submitResponse = { FakeExchangeServer.Response(403, """{"error":"forbidden"}""") }

        withMediator {
            enroll(tenant)
            releaseWithPublication(tenant, catalogKey)

            worker.run()

            assertThat(publication(tenant.id, catalogKey).status).isEqualTo(CatalogPublicationStatus.RETRY)
            assertThat(publication(tenant.id, catalogKey).attempts).isEqualTo(1)
            assertThat(credentials.connection(tenant.id)?.status).isEqualTo(ExchangeConnectionStatus.BLOCKED)
        }
    }

    @Test
    fun `an unauthorized submission asks for reauthorization`() {
        val tenant = createTenant("Worker Unauthorized")
        val catalogKey = CatalogKey.of("worker-unauthorized")
        exchange.submitResponse = { FakeExchangeServer.Response(401, """{"error":"invalid_token"}""") }

        withMediator {
            enroll(tenant)
            releaseWithPublication(tenant, catalogKey)

            worker.run()

            assertThat(credentials.connection(tenant.id)?.status)
                .isEqualTo(ExchangeConnectionStatus.REAUTHORIZATION_REQUIRED)
        }
    }

    /** Completes the redirect flow against the fake so the connection is seeded by production commands. */
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

    private fun releaseWithPublication(tenant: Tenant, catalogKey: CatalogKey) {
        CreateCatalog(tenant.id, catalogKey, catalogKey.value).execute()
        ReleaseCatalogVersion(tenant.id, catalogKey, "1.0.0", publication = ReleasePublication.PUBLISH).execute()
    }

    private fun publication(tenantKey: TenantKey, catalogKey: CatalogKey) = requireNotNull(GetCatalogPublicationState(tenantKey, catalogKey).query()).publications.single()

    /**
     * The worker defers the next look at a row using the database clock, which the test clock
     * cannot move, so a test that wants a second pass makes the row due again directly.
     */
    private fun forceDue(id: java.util.UUID) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate("UPDATE catalog_release_publications SET next_attempt_at = NOW() WHERE id = :id")
            .bind("id", id).execute()
    }

    companion object {
        private val exchange = FakeExchangeServer()

        @JvmStatic
        @DynamicPropertySource
        fun exchangeProperties(registry: DynamicPropertyRegistry) {
            registry.add("epistola.exchange.enabled") { "true" }
            registry.add("epistola.exchange.base-url") { exchange.baseUrl }
        }

        @JvmStatic
        @AfterAll
        fun stopExchange() = exchange.close()
    }
}
