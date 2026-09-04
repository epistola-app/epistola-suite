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
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.FakeExchangeServer
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Two nodes reaching for the same outbox rows.
 *
 * The publication worker is a `single_owner` cluster task, so in principle only one node runs it —
 * but the store is written not to rely on that, because "in principle" covers neither a scheduler
 * handover nor a node that is still finishing a batch while its replacement starts one. The claim
 * is `FOR UPDATE SKIP LOCKED` plus an expiring lease for exactly that reason.
 *
 * This is the one invariant in the feature whose failure is silent and expensive: two nodes
 * claiming the same row both submit it, and a duplicate submission is a release sent to Exchange
 * twice. The idempotency key makes that survivable, not correct.
 */
class CatalogPublicationClaimConcurrencyTest : IntegrationTestBase() {
    @Autowired
    private lateinit var store: CatalogPublicationStore

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @BeforeEach
    fun resetExchange() = exchange.reset()

    @Test
    fun `a poller steps over rows another node is holding rather than waiting for them`() {
        val tenant = createTenant("Claim Race")
        val queued = mutableListOf<UUID>()
        withMediator<Unit> {
            enroll(tenant)
            repeat(PUBLICATIONS) { index ->
                val key = CatalogKey.of("race-$index")
                CreateCatalog(tenant.id, key, key.value).execute()
                SetCatalogPublicationNamespace(tenant.id, key, "public-services").execute()
                ReleaseCatalogVersion(tenant.id, key, "1.0.0", publication = ReleasePublication.PUBLISH).execute()
            }
            queued += publicationIds()
        }
        assertThat(queued).hasSize(PUBLICATIONS)

        // Racing two threads and hoping they collide proves nothing - run that way, both claims
        // simply happen one after the other and every arrangement passes, locking or not. So the
        // overlap is made real instead: one node claims inside a transaction this test holds open,
        // which keeps its row locks held, and the other node claims while they are.
        val holdingClaim = CompletableFuture<List<UUID>>()
        val releaseHolder = CountDownLatch(1)
        val holder = Executors.newSingleThreadExecutor()
        val second = Executors.newSingleThreadExecutor()
        try {
            holder.submit(
                withMediator {
                    MediatorContext.runnable(mediator) {
                        TransactionTemplate(transactionManager).executeWithoutResult {
                            holdingClaim.complete(store.claimDue(PUBLICATIONS / 2).map(CatalogReleasePublication::id))
                            releaseHolder.await(HOLD_SECONDS, TimeUnit.SECONDS)
                        }
                    }
                },
            )
            val held = holdingClaim.get(HOLD_SECONDS, TimeUnit.SECONDS)
            assertThat(held).hasSize(PUBLICATIONS / 2)

            // On a separate thread with a timeout: without SKIP LOCKED this call blocks on the held
            // rows until the other transaction ends, and the test should say so rather than hang.
            val other =
                second.submit(
                    withMediator {
                        MediatorContext.callable(mediator) { store.claimDue(PUBLICATIONS * 2).map(CatalogReleasePublication::id) }
                    },
                )
            val taken = other.get(HOLD_SECONDS, TimeUnit.SECONDS)

            assertThat(taken)
                .describedAs("a row another node is holding must be stepped over, never claimed a second time")
                .doesNotContainAnyElementsOf(held)
            // Stepped over, not dropped: between them the two nodes still take all the work.
            assertThat(held + taken).containsExactlyInAnyOrderElementsOf(queued)
        } finally {
            releaseHolder.countDown()
            holder.shutdown()
            second.shutdown()
        }
    }

    @Test
    fun `a claim held by a node that never came back is taken over once the lease expires`() {
        val tenant = createTenant("Claim Lease")
        val key = CatalogKey.of("lease")
        withMediator<Unit> {
            enroll(tenant)
            CreateCatalog(tenant.id, key, key.value).execute()
            SetCatalogPublicationNamespace(tenant.id, key, "public-services").execute()
            ReleaseCatalogVersion(tenant.id, key, "1.0.0", publication = ReleasePublication.PUBLISH).execute()
        }

        val claimed = withMediator { store.claimDue(10) }
        assertThat(claimed).hasSize(1)

        // Still leased: a second poller must leave it alone rather than duplicate the work.
        assertThat(withMediator { store.claimDue(10) }).isEmpty()

        // The claiming node dies. `claimed_at` is written by the database clock and compared against
        // it, so the test clock cannot reach it - ageing the row directly is the documented exception.
        ageClaim(claimed.single().id)

        assertThat(withMediator { store.claimDue(10) }.map(CatalogReleasePublication::id))
            .describedAs("a lease that outlives its node must not strand the publication forever")
            .containsExactly(claimed.single().id)
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

    private fun publicationIds(): List<UUID> = jdbi.withHandle<List<UUID>, Exception> { handle ->
        handle.createQuery("SELECT id FROM catalog_release_publications ORDER BY created_at")
            .mapTo(UUID::class.java).list()
    }

    private fun ageClaim(id: UUID) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate("UPDATE catalog_release_publications SET claimed_at = NOW() - INTERVAL '30 minutes' WHERE id = :id")
            .bind("id", id).execute()
    }

    companion object {
        private const val PUBLICATIONS = 8

        /** Long enough to be a real wait, short enough that a blocked claim fails instead of hanging. */
        private const val HOLD_SECONDS = 15L
        private val exchange = FakeExchangeServer()

        @JvmStatic
        @DynamicPropertySource
        fun exchangeProperties(registry: DynamicPropertyRegistry) {
            registry.add("epistola.exchange.enabled") { "true" }
            registry.add("epistola.exchange.base-url") { exchange.baseUrl }
            registry.add("epistola.exchange.allow-http") { "true" }
        }
    }
}
