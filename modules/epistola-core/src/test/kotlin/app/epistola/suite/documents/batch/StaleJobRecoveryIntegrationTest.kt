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
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.documents.queries.GetGenerationJob
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

    @Autowired private lateinit var contentStore: app.epistola.suite.storage.DocumentContentStore

    @Autowired private lateinit var meterRegistry: io.micrometer.core.instrument.MeterRegistry

    @Autowired private lateinit var generationService: app.epistola.suite.generation.GenerationService

    @Autowired private lateinit var schemaValidator: app.epistola.suite.templates.validation.JsonSchemaValidator

    @Autowired private lateinit var localeResolver: app.epistola.suite.i18n.TenantLocaleResolver

    @Autowired private lateinit var releaseRenderSource: app.epistola.suite.generation.release.ReleaseRenderSource

    @Autowired private lateinit var draftRenderer: app.epistola.suite.generation.DraftDocumentRenderer

    // Integration tests wire a fake executor that never renders, so run the production one.
    private val realExecutor by lazy {
        DocumentGenerationExecutor(
            jdbi, generationService, mediator, objectMapper, schemaValidator, contentStore,
            meterRegistry, localeResolver, releaseRenderSource, draftRenderer,
            retentionDays = 7, maxDocumentSizeMb = 50,
        )
    }

    @Test
    fun `resets a stale in-progress request but leaves a freshly-claimed one`() = scenario {
        given {
            val tenant = tenant("Stale Recovery")
            val tenantId = TenantId(tenant.id)
            val template = template(tenant.id, "SR Template")
            val compositeTemplateId = TemplateId(template.id, CatalogId.default(tenantId))
            val variant = variant(compositeTemplateId, "Default")
            val compositeVariantId = VariantId(variant.id, compositeTemplateId)
            val version = released(compositeVariantId, TestTemplateBuilder.buildMinimal(name = "SR Template"))

            fun request(filename: String) = execute(
                GenerateDocument(
                    tenantId = tenant.id,
                    templateId = template.id,
                    variantId = variant.id,
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

    @Test
    fun `a request whose render keeps stopping its node fails with the reason instead of going round forever`() = scenario {
        given {
            val tenant = tenant("Stale Recovery Limit")
            val tenantId = TenantId(tenant.id)
            val template = template(tenant.id, "Crashing Template")
            val compositeTemplateId = TemplateId(template.id, CatalogId.default(tenantId))
            val variant = variant(compositeTemplateId, "Default")
            released(VariantId(variant.id, compositeTemplateId), TestTemplateBuilder.buildMinimal(name = "Crashing Template"))
            val request = execute(
                GenerateDocument(tenantId = tenant.id, templateId = template.id, variantId = variant.id, environmentId = null, data = objectMapper.createObjectNode(), filename = null),
            )
            // Three nodes in turn stop while rendering it.
            repeat(3) {
                markInProgress(request.id, ageMinutes = 20)
                staleJobRecovery.recoverStaleJobs()
            }
            request
        }.whenever { request ->
            val recovered = mediator.query(GetGenerationJob(request.tenantKey, request.id))!!.request
            realExecutor.execute(recovered)
            recovered to mediator.query(GetGenerationJob(request.tenantKey, request.id))!!.request
        }.then { _, (recovered, after) ->
            assertThat(recovered.recoveries).isEqualTo(3)
            assertThat(after.status).isEqualTo(RequestStatus.FAILED)
            assertThat(after.errorMessage).contains("stopped 3 times").contains("not tried again")
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
