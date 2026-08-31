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
            // Exchange now holds a release under these coordinates, so the namespace is fixed.
            assertThat(state(tenant.id, catalogKey).namespaceLocked).isTrue()
        }
    }

    @Test
    fun `a rejection is terminal and also releases the archive`() {
        val tenant = createTenant("Worker Rejects")
        val catalogKey = CatalogKey.of("worker-rejects")
        // A second granted namespace, so the catalog can be shown to still be movable afterwards.
        exchange.namespaces = listOf("public-services", "internal-forms")
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

            // Exchange took the submission and then refused it, so it is holding nothing under
            // these coordinates. Locking the namespace here would freeze a catalog that has never
            // published, and tell its author it had.
            assertThat(state(tenant.id, catalogKey).namespaceLocked).isFalse()
            SetCatalogPublicationNamespace(tenant.id, catalogKey, "internal-forms").execute()
            assertThat(state(tenant.id, catalogKey).boundNamespace).isEqualTo("internal-forms")
        }
    }

    @Test
    fun `a submission Exchange never decides is given up on instead of polled for ever`() {
        val tenant = createTenant("Worker Waits Out")
        val catalogKey = CatalogKey.of("worker-waits-out")

        withMediator {
            enroll(tenant)
            releaseWithPublication(tenant, catalogKey)
            worker.run()

            val submitted = publication(tenant.id, catalogKey)
            assertThat(submitted.status).isEqualTo(CatalogPublicationStatus.SUBMITTED)

            // The default status response accepts, so reaching FAILED rather than ACCEPTED is what
            // proves the worker stopped polling rather than simply getting a different answer.
            forceSubmittedLongAgo(submitted.id)
            forceDue(submitted.id)
            worker.run()

            val abandoned = publication(tenant.id, catalogKey)
            assertThat(abandoned.status).isEqualTo(CatalogPublicationStatus.FAILED)
            assertThat(abandoned.lastError).contains("has not decided this submission")
            // Terminal, but recoverable: the bytes are still there to retry or withdraw.
            assertThat(abandoned.archiveRetained).isTrue()
            assertThat(state(tenant.id, catalogKey).isRetry).isTrue()
        }
    }

    @Test
    fun `an unenrolled tenant queues nothing at all`() {
        val tenant = createTenant("Worker Waits")
        val catalogKey = CatalogKey.of("worker-waits")

        withMediator {
            SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_PUBLISHING, true).execute()
            releaseWithPublication(tenant, catalogKey, namespace = null)

            worker.run()

            // Not enrolled means no namespace, and no namespace means nothing was ever queued —
            // there is no work sitting in a queue that cannot move.
            assertThat(GetCatalogPublicationState(tenant.id, catalogKey).query()?.publications).isEmpty()
            assertThat(exchange.submittedIdempotencyKeys).isEmpty()
        }
    }

    @Test
    fun `choosing a namespace at publish time is enough on its own`() {
        val tenant = createTenant("Worker Chooses Namespace")
        val catalogKey = CatalogKey.of("worker-chooses-ns")
        exchange.namespaces = listOf("community", "epistola")

        withMediator {
            enroll(tenant)
            CreateCatalog(tenant.id, catalogKey, "Worker chooses ns").execute()

            // Nothing bound: publishing would have nowhere to go, and the form asks.
            assertThat(requireNotNull(GetCatalogPublicationState(tenant.id, catalogKey).query()).needsNamespaceChoice)
                .isTrue()

            // Picking one at the point of publishing is the whole setup — no visit to settings.
            SetCatalogPublicationNamespace(tenant.id, catalogKey, "epistola").execute()
            ReleaseCatalogVersion(tenant.id, catalogKey, "1.0.0", publication = ReleasePublication.PUBLISH).execute()
            worker.run()

            assertThat(publication(tenant.id, catalogKey).namespace).isEqualTo("epistola")
            assertThat(exchange.submittedNamespaces).containsExactly("epistola")
            assertThat(requireNotNull(GetCatalogPublicationState(tenant.id, catalogKey).query()).needsNamespaceChoice)
                .isFalse()
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

    /**
     * A catalog only queues once it has somewhere to publish, so binding is part of setting up a
     * publishable catalog rather than something the tenant default does behind the scenes.
     */
    private fun releaseWithPublication(tenant: Tenant, catalogKey: CatalogKey, namespace: String? = "public-services") {
        CreateCatalog(tenant.id, catalogKey, catalogKey.value).execute()
        namespace?.let { SetCatalogPublicationNamespace(tenant.id, catalogKey, it).execute() }
        ReleaseCatalogVersion(tenant.id, catalogKey, "1.0.0", publication = ReleasePublication.PUBLISH).execute()
    }

    private fun state(tenantKey: TenantKey, catalogKey: CatalogKey) = requireNotNull(GetCatalogPublicationState(tenantKey, catalogKey).query())

    private fun publication(tenantKey: TenantKey, catalogKey: CatalogKey) = state(tenantKey, catalogKey).publications.single()

    /**
     * The worker defers the next look at a row using the database clock, which the test clock
     * cannot move, so a test that wants a second pass makes the row due again directly.
     */
    private fun forceDue(id: java.util.UUID) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate("UPDATE catalog_release_publications SET next_attempt_at = NOW() WHERE id = :id")
            .bind("id", id).execute()
    }

    /**
     * `submitted_at` is written by the database, and the age the worker reads is computed there too,
     * so the test clock cannot reach it. Planting the historical timestamp directly is the documented
     * exception; the alternative is configuring a timeout no deployment would ever use.
     */
    private fun forceSubmittedLongAgo(id: java.util.UUID) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate("UPDATE catalog_release_publications SET submitted_at = NOW() - INTERVAL '48 hours' WHERE id = :id")
            .bind("id", id).execute()
    }

    companion object {
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
