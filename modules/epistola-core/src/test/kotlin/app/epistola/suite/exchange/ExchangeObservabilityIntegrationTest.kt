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
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration

/**
 * Publication is asynchronous, silent and default-off, so nothing tells an operator it stopped
 * working. These cover the two things that do: the installation gauges a monitoring system reads,
 * and the tenant-wide activity an administrator sees on the Exchange page.
 */
class ExchangeObservabilityIntegrationTest : ExchangeIntegrationTestBase() {

    @Autowired
    private lateinit var worker: CatalogPublicationWorker

    @Autowired
    private lateinit var metricsPublisher: ExchangeMetricsPublisher

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `installation gauges report the outbox state and are published by one replica`() {
        val tenant = createTenant("Observability Gauges")
        val catalogKey = CatalogKey.of("observability-gauges")

        withMediator {
            enroll(tenant)
            CreateCatalog(tenant.id, catalogKey, "Observability gauges").execute()
            SetCatalogPublicationNamespace(tenant.id, catalogKey, "public-services").execute()
            ReleaseCatalogVersion(tenant.id, catalogKey, "1.0.0", publication = ReleasePublication.PUBLISH).execute()

            metricsPublisher.publish()

            assertThat(gauge("epistola.installation.exchange_publications", "ready")).isGreaterThanOrEqualTo(1.0)
            assertThat(gauge("epistola.installation.exchange_publications", "accepted")).isZero()
            // Something is outstanding, so the age gauge is live.
            assertThat(
                meterRegistry.get("epistola.installation.exchange_publication_oldest_active_age_seconds").gauge().value(),
            ).isGreaterThanOrEqualTo(0.0)
            // A queued publication is holding its release ZIP, and that is the point of the gauge:
            // the bytes are real and nothing else in the installation accounts for them.
            assertThat(
                meterRegistry.get("epistola.installation.exchange_publication_retained_archive_bytes").gauge().value(),
            ).isGreaterThan(0.0)
        }
    }

    @Test
    fun `a submission that reaches a decision is counted, and a failure is counted separately`() {
        val tenant = createTenant("Observability Counters")
        val catalogKey = CatalogKey.of("observability-counters")
        exchange.submitResponse = {
            FakeExchangeServer.Response(200, exchange.publicationBody(exchange.remotePublicationId, "ACCEPTED"))
        }

        withMediator {
            enroll(tenant)
            CreateCatalog(tenant.id, catalogKey, "Observability counters").execute()
            SetCatalogPublicationNamespace(tenant.id, catalogKey, "public-services").execute()
            ReleaseCatalogVersion(tenant.id, catalogKey, "1.0.0", publication = ReleasePublication.PUBLISH).execute()

            // The worker drains every due publication in the installation, so assert the counter
            // moved for this outcome rather than pinning an exact total.
            val acceptedBefore = counter("epistola.exchange.publication.submissions", "accepted")
            worker.run()
            assertThat(counter("epistola.exchange.publication.submissions", "accepted")).isGreaterThan(acceptedBefore)

            // A second catalog whose submission never reaches Exchange counts as an error, not a decision.
            val other = CatalogKey.of("observability-counters-error")
            CreateCatalog(tenant.id, other, "Observability counters error").execute()
            SetCatalogPublicationNamespace(tenant.id, other, "public-services").execute()
            ReleaseCatalogVersion(tenant.id, other, "1.0.0", publication = ReleasePublication.PUBLISH).execute()
            exchange.submitResponse = { FakeExchangeServer.Response(500, """{"error":"boom"}""") }

            val errorsBefore = counter("epistola.exchange.publication.submissions", "error")
            worker.run()
            assertThat(counter("epistola.exchange.publication.submissions", "error")).isGreaterThan(errorsBefore)

            // Sending a release and asking about one are counted apart. Without the split, the polls
            // that follow every submission dominate the outcome and the publication rate is
            // unrecoverable from the series.
            assertThat(counter("epistola.exchange.publication.submissions", "accepted", "submit"))
                .describedAs("the accepted release was sent, so it is counted as a submission")
                .isGreaterThan(0.0)
            assertThat(counter("epistola.exchange.publication.submissions", "error", "submit"))
                .describedAs("the failed attempt never reached Exchange, so it is a submission too")
                .isGreaterThan(0.0)
        }
    }

    @Test
    fun `tenant activity summarises every catalog and flags work that is not progressing`() {
        val tenant = createTenant("Observability Activity")

        withMediator {
            enroll(tenant)
            listOf("activity-one", "activity-two").forEach { slug ->
                val key = CatalogKey.of(slug)
                CreateCatalog(tenant.id, key, slug).execute()
                SetCatalogPublicationNamespace(tenant.id, key, "public-services").execute()
                ReleaseCatalogVersion(tenant.id, key, "1.0.0", publication = ReleasePublication.PUBLISH).execute()
            }

            val activity = GetExchangePublicationActivity(tenant.id).query()

            // One view over both catalogs — the per-catalog page cannot answer this.
            assertThat(activity.total).isEqualTo(2)
            assertThat(activity.active).isEqualTo(2)
            assertThat(activity.recent.map { it.catalogKey.value }).containsExactlyInAnyOrder("activity-one", "activity-two")
            assertThat(activity.countsByStatus[CatalogPublicationStatus.READY]).isEqualTo(2)
            assertThat(activity.oldestActiveSince).isNotNull

            // Freshly queued work is not stalled.
            assertThat(activity.stalled).isFalse()

            // Raw SQL: the age is measured by the database clock (created_at is database-owned),
            // so simulating a stall means planting a historical timestamp, not moving the test clock.
            jdbi.useHandle<Exception> { handle ->
                handle.createUpdate(
                    "UPDATE catalog_release_publications SET created_at = NOW() - INTERVAL '2 hours' WHERE tenant_key = :tenantKey",
                ).bind("tenantKey", tenant.id).execute()
            }

            val stalled = GetExchangePublicationActivity(tenant.id).query()
            assertThat(stalled.stalled).isTrue()
            assertThat(stalled.oldestActive?.age).isGreaterThan(Duration.ofHours(1))
        }
    }

    private fun enroll(tenant: app.epistola.suite.tenants.Tenant) {
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

    private fun gauge(name: String, status: String) = meterRegistry.get(name).tag("status", status).gauge().value()

    /**
     * Summed across the `call` tag: an outcome is now recorded separately for a submission and for a
     * poll, and a total that silently picked one of them would drift as soon as either moved.
     */
    private fun counter(name: String, outcome: String) = meterRegistry.find(name).tag("outcome", outcome).counters().sumOf { it.count() }

    private fun counter(name: String, outcome: String, call: String) = meterRegistry.find(name).tag("outcome", outcome).tag("call", call).counter()?.count() ?: 0.0
}
