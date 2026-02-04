package app.epistola.suite.loadtest.commands

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.loadtest.batch.LoadTestCreatedEvent
import app.epistola.suite.loadtest.model.LoadTestRun
import app.epistola.suite.loadtest.model.LoadTestRunId
import app.epistola.suite.loadtest.model.LoadTestStatus
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
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
 * @property versionId Explicit version ID (mutually exclusive with environmentId)
 * @property environmentId Environment to determine version from (mutually exclusive with versionId)
 * @property targetCount Number of documents to generate (1-10000)
 * @property concurrencyLevel Number of concurrent requests (1-500)
 * @property testData JSON data to use for all document generation requests
 */
data class StartLoadTest(
    val tenantId: TenantId,
    val templateId: TemplateId,
    val variantId: VariantId,
    val versionId: VersionId?,
    val environmentId: EnvironmentId?,
    val targetCount: Int,
    val concurrencyLevel: Int,
    val testData: ObjectNode,
) : Command<LoadTestRun> {
    init {
        // Validate that exactly one of versionId or environmentId is set
        require((versionId != null) xor (environmentId != null)) {
            "Exactly one of versionId or environmentId must be set"
        }
        require(targetCount in 1..10000) {
            "Target count must be between 1 and 10000, got $targetCount"
        }
        require(concurrencyLevel in 1..500) {
            "Concurrency level must be between 1 and 500, got $concurrencyLevel"
        }
    }
}

@Component
class StartLoadTestHandler(
    private val jdbi: Jdbi,
    private val eventPublisher: ApplicationEventPublisher,
) : CommandHandler<StartLoadTest, LoadTestRun> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: StartLoadTest): LoadTestRun {
        logger.info(
            "Starting load test for tenant {} template {} - {} docs at {} concurrency",
            command.tenantId,
            command.templateId,
            command.targetCount,
            command.concurrencyLevel,
        )

        val loadTestRun = jdbi.inTransaction<LoadTestRun, Exception> { handle ->
            // 1. Verify template exists and belongs to tenant
            val templateExists = handle.createQuery(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM document_templates dt
                    JOIN template_variants tv ON dt.id = tv.template_id
                    WHERE dt.id = :templateId
                      AND tv.id = :variantId
                      AND dt.tenant_id = :tenantId
                )
                """,
            )
                .bind("templateId", command.templateId)
                .bind("variantId", command.variantId)
                .bind("tenantId", command.tenantId)
                .mapTo<Boolean>()
                .one()

            require(templateExists) {
                "Template ${command.templateId} variant ${command.variantId} not found for tenant ${command.tenantId}"
            }

            // 2. Verify version or environment exists
            if (command.versionId != null) {
                val versionExists = handle.createQuery(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM template_versions ver
                        JOIN template_variants tv ON ver.variant_id = tv.id
                        JOIN document_templates dt ON tv.template_id = dt.id
                        WHERE ver.id = :versionId
                          AND ver.variant_id = :variantId
                          AND tv.template_id = :templateId
                          AND dt.tenant_id = :tenantId
                    )
                    """,
                )
                    .bind("versionId", command.versionId)
                    .bind("variantId", command.variantId)
                    .bind("templateId", command.templateId)
                    .bind("tenantId", command.tenantId)
                    .mapTo<Boolean>()
                    .one()

                require(versionExists) {
                    "Version ${command.versionId} not found for template ${command.templateId} variant ${command.variantId}"
                }
            } else {
                val environmentExists = handle.createQuery(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM environments
                        WHERE id = :environmentId
                          AND tenant_id = :tenantId
                    )
                    """,
                )
                    .bind("environmentId", command.environmentId)
                    .bind("tenantId", command.tenantId)
                    .mapTo<Boolean>()
                    .one()

                require(environmentExists) {
                    "Environment ${command.environmentId} not found for tenant ${command.tenantId}"
                }
            }

            // 3. Create load test run (stays in PENDING status for poller to pick up)
            val runId = LoadTestRunId.generate()
            val run = handle.createQuery(
                """
                INSERT INTO load_test_runs (
                    id, tenant_id, template_id, variant_id, version_id, environment_id,
                    target_count, concurrency_level, test_data, status
                )
                VALUES (:id, :tenantId, :templateId, :variantId, :versionId, :environmentId,
                        :targetCount, :concurrencyLevel, :testData::jsonb, :status)
                RETURNING id, tenant_id, template_id, variant_id, version_id, environment_id,
                          target_count, concurrency_level, test_data, status, claimed_by, claimed_at,
                          completed_count, failed_count, total_duration_ms, avg_response_time_ms,
                          min_response_time_ms, max_response_time_ms, p50_response_time_ms,
                          p95_response_time_ms, p99_response_time_ms, requests_per_second,
                          success_rate_percent, error_summary, created_at, started_at, completed_at
                """,
            )
                .bind("id", runId)
                .bind("tenantId", command.tenantId)
                .bind("templateId", command.templateId)
                .bind("variantId", command.variantId)
                .bind("versionId", command.versionId)
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
