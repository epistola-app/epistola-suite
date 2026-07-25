// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.batch

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.ScenarioBuilder
import app.epistola.suite.testing.TestTemplateBuilder
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper

/**
 * Behavioural coverage for [StaleJobRecovery]: a genuinely stale IN_PROGRESS request is
 * reset to PENDING; a freshly-claimed one is left alone.
 *
 * Note this does not exercise the concurrent-sweep race that motivated folding the
 * recovery into a single atomic conditional UPDATE (a SELECT-then-update-by-id could
 * clobber a row re-claimed between the two statements once the task runs on every node).
 * That race can't be reproduced deterministically; its fix is by SQL construction — the
 * staleness predicate lives in the UPDATE and Postgres re-evaluates it under the row
 * lock. This test guards that recovery stays threshold-conditional.
 */
class StaleJobRecoveryIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var staleJobRecovery: StaleJobRecovery

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `resets a stale in-progress request but leaves a freshly-claimed one`() = scenario {
        given {
            val tenant = tenant("Stale Recovery")
            val tenantId = TenantId(tenant.id)
            val template = template(tenant.id, "SR Template")
            val compositeTemplateId = TemplateId(template.id, CatalogId.default(tenantId))
            val variant = variant(compositeTemplateId, "Default")
            val compositeVariantId = VariantId(variant.id, compositeTemplateId)
            val version = version(compositeVariantId, TestTemplateBuilder.buildMinimal(name = "SR Template"))

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

            // Default stale-timeout is 10 min; seed ages relative to DB NOW() since
            // recovery keys off NOW() - interval, not the test clock.
            val stale = request("stale.pdf").also { markInProgress(it.id, ageMinutes = 20) }
            val fresh = request("fresh.pdf").also { markInProgress(it.id, ageMinutes = 1) }
            stale.id to fresh.id
        }.whenever { (staleId, freshId) ->
            staleJobRecovery.recoverStaleJobs()
            statusOf(staleId) to statusOf(freshId)
        }.then { _, (staleStatus, freshStatus) ->
            assertThat(staleStatus).isEqualTo("PENDING")
            assertThat(freshStatus).isEqualTo("IN_PROGRESS")
        }
    }

    private fun ScenarioBuilder.GivenScope.markInProgress(requestId: GenerationRequestKey, ageMinutes: Int) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE document_generation_requests
                SET status = 'IN_PROGRESS',
                    claimed_by = 'seed-node',
                    claimed_at = NOW() - make_interval(mins => :age),
                    started_at = NOW() - make_interval(mins => :age)
                WHERE id = :id
                """,
            )
                .bind("id", requestId)
                .bind("age", ageMinutes)
                .execute()
        }
    }

    private fun statusOf(requestId: GenerationRequestKey): String = jdbi.withHandle<String, Exception> { handle ->
        handle.createQuery("SELECT status FROM document_generation_requests WHERE id = :id")
            .bind("id", requestId)
            .mapTo<String>()
            .one()
    }
}
