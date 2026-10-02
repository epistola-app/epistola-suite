// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.loadtest.commands

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.generation.release.ReleaseTargetResolver
import app.epistola.suite.loadtest.batch.LoadTestCreatedEvent
import app.epistola.suite.loadtest.model.LoadTestRun
import app.epistola.suite.loadtest.model.LoadTestRunKey
import app.epistola.suite.loadtest.model.LoadTestStatus
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.SelfManagedTransaction
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.templateAtAddress
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode

/**
 * Command to start a new load test run.
 *
 * @property tenantId Tenant that owns the template
 * @property templateId Template to use for load testing
 * @property variantId Variant of the template
 * @property environmentId Environment whose deployed release of the template's catalog is rendered;
 *   without one, the catalog's latest release is rendered, exactly as for generation
 * @property targetCount Number of documents to generate (1-10000)
 * @property concurrencyLevel Legacy field (not used, kept for database compatibility)
 * @property testData JSON data to use for all document generation requests
 */
data class StartLoadTest(
    val tenantId: TenantKey,
    val catalogKey: CatalogKey = CatalogKey.DEFAULT,
    val templateId: TemplateKey,
    val variantId: VariantKey,
    val environmentId: EnvironmentKey? = null,
    val targetCount: Int,
    val concurrencyLevel: Int,
    val testData: ObjectNode,
) : Command<LoadTestRun>,
    RequiresPermission,
    // Long-running load-test bootstrap; owns its transaction boundaries.
    SelfManagedTransaction {
    override val permission get() = Permission.DOCUMENT_GENERATE
    override val tenantKey get() = tenantId

    init {
        require(targetCount in 1..10000) {
            "Target count must be between 1 and 10000, got $targetCount"
        }
    }
}

@Component
class StartLoadTestHandler(
    private val jdbi: Jdbi,
    private val eventPublisher: ApplicationEventPublisher,
    private val releaseTargetResolver: ReleaseTargetResolver,
) : CommandHandler<StartLoadTest, LoadTestRun> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: StartLoadTest): LoadTestRun {
        logger.info(
            "Starting load test for tenant {} template {} - {} docs",
            command.tenantId,
            command.templateId,
            command.targetCount,
        )

        val loadTestRun = jdbi.inTransaction<LoadTestRun, Exception> { handle ->
            // 1. Verify template/variant exists and belongs to tenant
            val templateExists = handle.createQuery(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM template_variants
                    WHERE tenant_key = :tenantId AND id = :variantId
                      AND template_resource_id = ${templateAtAddress("tenantId", "catalogKey", "templateId")}
                )
                """,
            )
                .bind("templateId", command.templateId)
                .bind("catalogKey", command.catalogKey)
                .bind("variantId", command.variantId)
                .bind("tenantId", command.tenantId)
                .mapTo<Boolean>()
                .one()

            require(templateExists) {
                "Template ${command.templateId} variant ${command.variantId} not found for tenant ${command.tenantId}"
            }

            // 2. Resolve the release the run will render, as generation will: the release the
            //    environment serves, or the catalog's latest. Failing here, with generation's own
            //    error, beats a run that is accepted and then fails every one of its requests.
            if (command.environmentId != null) {
                releaseTargetResolver.resolveDeployed(command.tenantId, command.environmentId, command.catalogKey, command.templateId, command.variantId, null)
            } else {
                releaseTargetResolver.resolveLatest(command.tenantId, command.catalogKey, command.templateId, command.variantId, null)
            }

            // 3. Create load test run (stays in PENDING status for poller to pick up)
            val runId = LoadTestRunKey.generate()
            val run = handle.createQuery(
                """
                INSERT INTO load_test_runs (
                    id, tenant_key, template_resource_id, variant_key, version_key, environment_key,
                    target_count, concurrency_level, test_data, status
                )
                VALUES (:id, :tenantId, ${templateAtAddress("tenantId", "catalogKey", "templateId")}, :variantId, NULL, :environmentId,
                        :targetCount, :concurrencyLevel, :testData::jsonb, :status)
                RETURNING id, tenant_key, CAST(:catalogKey AS TEXT) AS catalog_key,
                          CAST(:templateId AS TEXT) AS template_key,
                          variant_key, version_key, environment_key,
                          target_count, concurrency_level, test_data, status, claimed_by, claimed_at,
                          completed_count, failed_count, total_duration_ms, avg_response_time_ms,
                          min_response_time_ms, max_response_time_ms, p50_response_time_ms,
                          p95_response_time_ms, p99_response_time_ms, requests_per_second,
                          success_rate_percent, error_summary, created_at, started_at, completed_at
                """,
            )
                .bind("id", runId)
                .bind("tenantId", command.tenantId)
                .bind("catalogKey", command.catalogKey)
                .bind("templateId", command.templateId)
                .bind("variantId", command.variantId)
                .bind("environmentId", command.environmentId)
                .bind("targetCount", command.targetCount)
                .bind("concurrencyLevel", command.concurrencyLevel)
                .bind("testData", command.testData.toString())
                .bind("status", LoadTestStatus.PENDING.name)
                .mapTo<LoadTestRun>()
                .one()

            logger.info("Created load test run {} for tenant {}", run.id, command.tenantId)

            // Run stays in PENDING status - the LoadTestPoller will pick it up
            run
        }

        // Publish event AFTER transaction commits for synchronous execution in tests
        eventPublisher.publishEvent(LoadTestCreatedEvent(loadTestRun))

        return loadTestRun
    }
}
