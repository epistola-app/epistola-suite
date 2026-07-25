// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.loadtest.batch

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.loadtest.commands.StartLoadTest
import app.epistola.suite.loadtest.model.LoadTestRunKey
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.ScenarioBuilder
import app.epistola.suite.testing.TestTemplateBuilder
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.OffsetDateTime

/**
 * Stale-run recovery must recover a genuinely abandoned run (dead executor) but
 * NOT a healthy long run that is still making progress. Before #725 staleness was
 * judged by claim age alone, so a healthy run that had simply been RUNNING longer
 * than the timeout was reset to PENDING and re-executed — submitting a second full
 * batch and corrupting the run's metrics. Recovery now keys off the progress
 * heartbeat (`last_progress_at`).
 */
class LoadTestPollerRecoveryIT : IntegrationTestBase() {

    @Autowired
    private lateinit var poller: LoadTestPoller

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `does not recover a running run that is still making progress`() = scenario {
        given {
            // Old claim (30 min) but fresh progress (1 min) — a healthy long run.
            seedRunningRun(claimAgeMinutes = 30, progressAgeMinutes = 1)
        }.whenever { runId ->
            poller.recoverStaleTests()
            statusOf(runId)
        }.then { _, status ->
            assertThat(status).isEqualTo("RUNNING")
        }
    }

    @Test
    fun `recovers a running run whose progress heartbeat has gone stale`() = scenario {
        given {
            // No progress for 30 min — a genuinely abandoned run (dead executor).
            seedRunningRun(claimAgeMinutes = 30, progressAgeMinutes = 30)
        }.whenever { runId ->
            poller.recoverStaleTests()
            statusOf(runId)
        }.then { _, status ->
            assertThat(status).isEqualTo("PENDING")
        }
    }

    /**
     * Builds a template/variant/version, starts a load test (PENDING) through the
     * real command, then forces it to RUNNING with controlled claim/progress ages
     * (the historical-timestamp + non-default-lifecycle exception to the
     * seed-through-commands rule — no command produces a stale RUNNING row).
     */
    private fun ScenarioBuilder.GivenScope.seedRunningRun(claimAgeMinutes: Long, progressAgeMinutes: Long): LoadTestRunKey {
        val tenant = tenant("LT Recovery")
        val tenantId = TenantId(tenant.id)
        val template = template(tenant.id, "LT Template")
        val compositeTemplateId = TemplateId(template.id, CatalogId.default(tenantId))
        val variant = variant(compositeTemplateId, "Default")
        val compositeVariantId = VariantId(variant.id, compositeTemplateId)
        val version = version(compositeVariantId, TestTemplateBuilder.buildMinimal(name = "LT Template"))

        val run = execute(
            StartLoadTest(
                tenantId = tenant.id,
                templateId = template.id,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                targetCount = 100,
                concurrencyLevel = 1,
                testData = objectMapper.createObjectNode() as ObjectNode,
            ),
        )

        val now = OffsetDateTime.now(testClock)
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE load_test_runs
                SET status = 'RUNNING',
                    claimed_by = 'other-node-1',
                    claimed_at = :claimedAt,
                    started_at = :claimedAt,
                    last_progress_at = :progressAt
                WHERE id = :id
                """,
            )
                .bind("id", run.id)
                .bind("claimedAt", now.minusMinutes(claimAgeMinutes))
                .bind("progressAt", now.minusMinutes(progressAgeMinutes))
                .execute()
        }
        return run.id
    }

    private fun statusOf(runId: LoadTestRunKey): String = jdbi.withHandle<String, Exception> { handle ->
        handle.createQuery("SELECT status FROM load_test_runs WHERE id = :id")
            .bind("id", runId)
            .mapTo<String>()
            .one()
    }
}
