// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.batch

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.database.DatabasePressureSnapshot
import app.epistola.suite.database.DatabasePressureSource
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.ScenarioBuilder
import app.epistola.suite.testing.TestTemplateBuilder
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import tools.jackson.databind.ObjectMapper
import java.time.Duration

/**
 * Verifies the feature's central safety claim: [DatabasePressureAdmissionController]
 * only ever gates *new* claims. Reducing the effective limit mid-drain must never
 * reach back into a request that is already IN_PROGRESS, and must resume admitting
 * once pressure clears.
 *
 * A test-local [DatabasePressureSource] double (wired [Primary] over the real
 * [app.epistola.suite.database.DatabasePressureMonitor]) drives the admission
 * controller deterministically, instead of provoking real database latency.
 */
@Isolated(
    "Drives JobPoller's global (non-tenant-scoped) drain() loop directly via requestDrain(); " +
        "must not race a concurrently-running test's background poller for globally-pending rows",
)
@Import(JobPollerDatabasePressureIntegrationTest.TestPressureSourceConfiguration::class)
class JobPollerDatabasePressureIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jobPoller: JobPoller

    @Autowired
    private lateinit var pressureSource: MutableDatabasePressureSource

    @Autowired
    private lateinit var jdbi: Jdbi

    private val objectMapper = ObjectMapper()

    @Test
    fun `reducing the effective limit mid-drain blocks new claims but never touches an in-flight request`() = scenario {
        given {
            val tenant = tenant("DB Pressure")
            val tenantId = TenantId(tenant.id)
            val template = template(tenant.id, "DB Pressure Template")
            val compositeTemplateId = TemplateId(template.id, CatalogId.default(tenantId))
            val variant = variant(compositeTemplateId, "Default")
            val compositeVariantId = VariantId(variant.id, compositeTemplateId)
            val version = version(compositeVariantId, TestTemplateBuilder.buildMinimal(name = "DB Pressure Template"))

            fun request(filename: String) = execute(
                GenerateDocument(
                    tenantId = tenant.id,
                    templateId = template.id,
                    variantId = variant.id,
                    versionId = version.id,
                    environmentId = null,
                    data = objectMapper.createObjectNode(),
                    filename = filename,
                ),
            )

            // No command produces "already claimed" state, so this seeds it directly --
            // simulating a render genuinely in flight when pressure hits.
            val inFlight = request("in-flight.pdf").also { markInProgress(it.id) }
            val blocked = request("blocked.pdf")
            inFlight.id to blocked.id
        }.whenever { (inFlightId, blockedId) ->
            // A critical failure pauses admissions to zero immediately, regardless of hysteresis.
            pressureSource.snapshot = DatabasePressureSnapshot(5, null, 0, true)
            jobPoller.awaitDrain()

            val inFlightStatusWhilePaused = statusOf(inFlightId)
            val blockedStatusWhilePaused = statusOf(blockedId)

            // Healthy again: this first drain establishes the healthy-since timestamp but does
            // not recover yet (the controller requires a *sustained* healthy period). Advancing
            // the clock past recoveryPeriodMs, then draining again, steps PAUSED -> RECOVERING
            // at the minimum, admitting the previously-blocked request.
            pressureSource.snapshot = DatabasePressureSnapshot(5, 50, 0, false)
            jobPoller.awaitDrain()
            testClock.advanceBy(Duration.ofSeconds(31))
            jobPoller.awaitDrain()
            jobPoller.awaitIdle()

            PressureDrainResult(
                inFlightStatusWhilePaused = inFlightStatusWhilePaused,
                blockedStatusWhilePaused = blockedStatusWhilePaused,
                blockedStatusAfterRecovery = statusOf(blockedId),
                inFlightStatusAfterRecovery = statusOf(inFlightId),
            )
        }.then { _, result ->
            assertThat(result.inFlightStatusWhilePaused).isEqualTo("IN_PROGRESS")
            assertThat(result.blockedStatusWhilePaused).isEqualTo("PENDING")
            assertThat(result.blockedStatusAfterRecovery).isEqualTo("COMPLETED")
            assertThat(result.inFlightStatusAfterRecovery).isEqualTo("IN_PROGRESS")
        }
    }

    private fun ScenarioBuilder.GivenScope.markInProgress(requestId: GenerationRequestKey) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE document_generation_requests
                SET status = 'IN_PROGRESS',
                    claimed_by = 'pressure-test-node',
                    claimed_at = NOW(),
                    started_at = NOW()
                WHERE id = :id
                """,
            )
                .bind("id", requestId)
                .execute()
        }
    }

    private fun statusOf(requestId: GenerationRequestKey): String = jdbi.withHandle<String, Exception> { handle ->
        handle.createQuery("SELECT status FROM document_generation_requests WHERE id = :id")
            .bind("id", requestId)
            .mapTo<String>()
            .one()
    }

    @TestConfiguration
    class TestPressureSourceConfiguration {
        @Bean
        @Primary
        fun databasePressureSource(): MutableDatabasePressureSource = MutableDatabasePressureSource()
    }
}

private data class PressureDrainResult(
    val inFlightStatusWhilePaused: String,
    val blockedStatusWhilePaused: String,
    val blockedStatusAfterRecovery: String,
    val inFlightStatusAfterRecovery: String,
)

/** Volatile: written on the test thread, read from JobPoller's dedicated drain thread. */
class MutableDatabasePressureSource : DatabasePressureSource {
    @Volatile
    var snapshot = DatabasePressureSnapshot(0, null, 0, false)

    override fun snapshot(nowMs: Long): DatabasePressureSnapshot = snapshot
}
