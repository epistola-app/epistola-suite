package app.epistola.suite.documents.commands

import app.epistola.suite.common.ids.BatchId
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.GenerationRequestId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.templates.services.VariantResolver
import app.epistola.suite.templates.services.VariantSelectionCriteria
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode

/**
 * Individual item in a batch generation request.
 *
 * Variant can be specified either explicitly via [variantId] or resolved automatically
 * via [variantSelectionCriteria]. Exactly one of the two must be set.
 */
data class BatchGenerationItem(
    val templateId: TemplateId,
    val variantId: VariantId? = null,
    val variantSelectionCriteria: VariantSelectionCriteria? = null,
    val versionId: VersionId?,
    val environmentId: EnvironmentId?,
    val data: ObjectNode,
    val filename: String?,
    val correlationId: String? = null,
) {
    init {
        require((variantId != null) xor (variantSelectionCriteria != null)) {
            "Exactly one of variantId or variantSelectionCriteria must be set"
        }
        require((versionId != null) xor (environmentId != null)) {
            "Exactly one of versionId or environmentId must be set"
        }
    }
}

/**
 * Exception thrown when batch validation fails due to duplicate correlationIds or filenames.
 */
class BatchValidationException(
    val duplicateCorrelationIds: List<String>,
    val duplicateFilenames: List<String>,
) : IllegalArgumentException(buildMessage(duplicateCorrelationIds, duplicateFilenames)) {
    companion object {
        private fun buildMessage(correlationIds: List<String>, filenames: List<String>): String {
            val parts = mutableListOf<String>()
            if (correlationIds.isNotEmpty()) {
                parts.add("Duplicate correlationIds: ${correlationIds.joinToString(", ")}")
            }
            if (filenames.isNotEmpty()) {
                parts.add("Duplicate filenames: ${filenames.joinToString(", ")}")
            }
            return parts.joinToString("; ")
        }
    }
}

/**
 * Command to generate multiple documents asynchronously in a batch.
 *
 * Creates N requests (one per item) grouped by a batch_id.
 *
 * @property tenantId Tenant that owns the templates
 * @property items List of items to generate
 */
data class GenerateDocumentBatch(
    val tenantId: TenantId,
    val items: List<BatchGenerationItem>,
) : Command<BatchId> {
    init {
        require(items.isNotEmpty()) { "At least one item is required" }
        validateUniqueness()
    }

    private fun validateUniqueness() {
        val duplicateCorrelationIds = items.mapNotNull { it.correlationId }
            .groupingBy { it }.eachCount().filter { it.value > 1 }.keys.toList()
        val duplicateFilenames = items.mapNotNull { it.filename }
            .groupingBy { it }.eachCount().filter { it.value > 1 }.keys.toList()

        if (duplicateCorrelationIds.isNotEmpty() || duplicateFilenames.isNotEmpty()) {
            throw BatchValidationException(duplicateCorrelationIds, duplicateFilenames)
        }
    }
}

@Component
class GenerateDocumentBatchHandler(
    private val jdbi: Jdbi,
    private val variantResolver: VariantResolver,
) : CommandHandler<GenerateDocumentBatch, BatchId> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: GenerateDocumentBatch): BatchId {
        logger.info("Generating batch of {} documents for tenant {}", command.items.size, command.tenantId)

        // Pre-resolve all variants from criteria before entering the transaction
        val resolvedVariantIds = command.items.map { item ->
            item.variantId
                ?: variantResolver.resolve(command.tenantId, item.templateId, item.variantSelectionCriteria!!)
        }

        val batchId = jdbi.inTransaction<BatchId, Exception> { handle ->
            // 1. Validate all templates/variants/versions/environments exist
            for ((index, item) in command.items.withIndex()) {
                val resolvedVariantId = resolvedVariantIds[index]

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
                    .bind("templateId", item.templateId)
                    .bind("variantId", resolvedVariantId)
                    .bind("tenantId", command.tenantId)
                    .mapTo<Boolean>()
                    .one()

                require(templateExists) {
                    "Item $index: Template ${item.templateId} variant $resolvedVariantId not found for tenant ${command.tenantId}"
                }

                if (item.versionId != null) {
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
                        .bind("versionId", item.versionId)
                        .bind("variantId", resolvedVariantId)
                        .bind("templateId", item.templateId)
                        .bind("tenantId", command.tenantId)
                        .mapTo<Boolean>()
                        .one()

                    require(versionExists) {
                        "Item $index: Version ${item.versionId} not found for template ${item.templateId} variant $resolvedVariantId"
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
                        .bind("environmentId", item.environmentId)
                        .bind("tenantId", command.tenantId)
                        .mapTo<Boolean>()
                        .one()

                    require(environmentExists) {
                        "Item $index: Environment ${item.environmentId} not found for tenant ${command.tenantId}"
                    }
                }
            }

            // 2. Create batch metadata
            val batchId = BatchId.generate()
            handle.createUpdate(
                """
                INSERT INTO document_generation_batches (
                    id, tenant_id, total_count
                )
                VALUES (:batchId, :tenantId, :totalCount)
                """,
            )
                .bind("batchId", batchId)
                .bind("tenantId", command.tenantId)
                .bind("totalCount", command.items.size)
                .execute()

            // 3. Create N requests (one per item) with batch_id
            val batch = handle.prepareBatch(
                """
                INSERT INTO document_generation_requests (
                    id, batch_id, tenant_id, template_id, variant_id, version_id, environment_id,
                    data, filename, correlation_id, document_id, status
                )
                VALUES (:id, :batchId, :tenantId, :templateId, :variantId, :versionId, :environmentId,
                        :data::jsonb, :filename, :correlationId, NULL, :status)
                """,
            )

            for ((index, item) in command.items.withIndex()) {
                val requestId = GenerationRequestId.generate()
                batch.bind("id", requestId)
                    .bind("batchId", batchId)
                    .bind("tenantId", command.tenantId)
                    .bind("templateId", item.templateId)
                    .bind("variantId", resolvedVariantIds[index])
                    .bind("versionId", item.versionId)
                    .bind("environmentId", item.environmentId)
                    .bind("data", item.data.toString())
                    .bind("filename", item.filename)
                    .bind("correlationId", item.correlationId)
                    .bind("status", RequestStatus.PENDING.name)
                    .add()
            }

            val inserted = batch.execute().sum()
            logger.info("Created batch {} with {} requests for tenant {}", batchId.value, inserted, command.tenantId)

            // Requests stay in PENDING status - the JobPoller will pick them up
            batchId
        }

        return batchId
    }
}
