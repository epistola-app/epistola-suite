// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.commands

import app.epistola.suite.catalog.revisions.ReleaseDependencyStore
import app.epistola.suite.common.NotAudited
import app.epistola.suite.common.NotEventLogged
import app.epistola.suite.common.ids.BatchKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.documents.CatalogNotReleasedException
import app.epistola.suite.documents.DefaultVariantNotFoundException
import app.epistola.suite.documents.EnvironmentNotFoundException
import app.epistola.suite.documents.NoPublishedVersionException
import app.epistola.suite.documents.TemplateVariantNotFoundException
import app.epistola.suite.documents.VersionNotFoundException
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.documents.versionGenerationRemoved
import app.epistola.suite.generation.release.ReleaseRef
import app.epistola.suite.generation.release.ReleaseTargetResolver
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.services.VariantResolver
import app.epistola.suite.templates.services.VariantSelectionCriteria
import app.epistola.suite.templates.templateAtAddress
import app.epistola.suite.templates.templateJoin
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
    val catalogKey: CatalogKey = CatalogKey.DEFAULT,
    val templateId: TemplateKey,
    val variantId: VariantKey? = null,
    val variantSelectionCriteria: VariantSelectionCriteria? = null,
    val versionId: VersionKey? = null,
    val environmentId: EnvironmentKey? = null,
    val data: ObjectNode,
    val filename: String?,
    val correlationId: String? = null,
    val routingKey: String? = null,
) {
    init {
        require(variantId == null || variantSelectionCriteria == null) {
            "Cannot specify both variantId and variantSelectionCriteria"
        }
        require(!(versionId != null && environmentId != null)) {
            "Cannot specify both versionId and environmentId"
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
    val tenantId: TenantKey,
    val items: List<BatchGenerationItem>,
    /**
     * Default routing key applied to items that don't specify their own. Per the v0.3 spec:
     * item-level wins; this is the batch-level fallback; if both are absent the emitter
     * uses the request id at terminal-state time.
     */
    val batchRoutingKey: String? = null,
) : Command<BatchKey>,
    RequiresPermission,
    NotAudited,
    NotEventLogged {
    override val permission get() = Permission.DOCUMENT_GENERATE
    override val tenantKey get() = tenantId

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
    private val releaseTargetResolver: ReleaseTargetResolver,
    private val dependencyStore: ReleaseDependencyStore,
) : CommandHandler<GenerateDocumentBatch, BatchKey> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: GenerateDocumentBatch): BatchKey {
        logger.info("Generating batch of {} documents for tenant {}", command.items.size, command.tenantId)
        if (command.items.any { it.versionId != null }) throw versionGenerationRemoved()

        // Items without an environment render their catalog's latest release. Resolved once per
        // catalog, so every item of one catalog in this batch renders the same release even if
        // another is cut while the batch is being accepted.
        val latestByCatalog = command.items
            .filter { it.environmentId == null }
            .map { it.catalogKey }
            .distinct()
            .associateWith { catalog ->
                jdbi.withHandle<String?, Exception> { handle -> dependencyStore.latestRetainedRelease(handle, command.tenantId, catalog) }
                    ?: throw CatalogNotReleasedException(command.tenantId, catalog)
            }
        val targets = command.items.map { item ->
            latestByCatalog[item.catalogKey]?.takeIf { item.environmentId == null }?.let { version ->
                releaseTargetResolver.resolveIn(
                    command.tenantId,
                    ReleaseRef(item.catalogKey, version),
                    item.templateId,
                    item.variantId,
                    item.variantSelectionCriteria,
                )
            }
        }

        // Environment items still resolve their variant in the working copy until environments
        // deploy releases.
        val resolvedVariantIds = command.items.mapIndexed { index, item ->
            targets[index]?.variantKey
                ?: item.variantId
                ?: item.variantSelectionCriteria?.let { variantResolver.resolve(TemplateId(item.templateId, CatalogId(item.catalogKey, TenantId(command.tenantId))), it) }
                ?: resolveDefaultVariant(command.tenantId, item.catalogKey, item.templateId)
        }

        val batchId = jdbi.inTransaction<BatchKey, Exception> { handle ->
            for ((index, item) in command.items.withIndex()) {
                if (item.environmentId == null) continue
                val resolvedVariantId = resolvedVariantIds[index]
                val templateExists = handle.createQuery(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM template_variants
                        WHERE tenant_key = :tenantId AND id = :variantId AND template_resource_id = ${templateAtAddress("tenantId", "catalogKey", "templateId")}
                    )
                    """,
                )
                    .bind("templateId", item.templateId)
                    .bind("catalogKey", item.catalogKey)
                    .bind("variantId", resolvedVariantId)
                    .bind("tenantId", command.tenantId)
                    .mapTo<Boolean>()
                    .one()
                if (!templateExists) {
                    throw TemplateVariantNotFoundException(command.tenantId, item.templateId, resolvedVariantId)
                }

                val environmentExists = handle.createQuery(
                    "SELECT EXISTS (SELECT 1 FROM environments WHERE id = :environmentId AND tenant_key = :tenantId)",
                )
                    .bind("environmentId", item.environmentId)
                    .bind("tenantId", command.tenantId)
                    .mapTo<Boolean>()
                    .one()
                if (!environmentExists) {
                    throw EnvironmentNotFoundException(command.tenantId, item.environmentId)
                }
            }

            val batchId = BatchKey.generate()
            handle.createUpdate(
                """
                INSERT INTO document_generation_batches (
                    id, tenant_key, total_count
                )
                VALUES (:batchId, :tenantId, :totalCount)
                """,
            )
                .bind("batchId", batchId)
                .bind("tenantId", command.tenantId)
                .bind("totalCount", command.items.size)
                .execute()

            val batch = handle.prepareBatch(
                """
                INSERT INTO document_generation_requests (
                    id, batch_id, tenant_key, catalog_key, template_key, variant_key, version_key, environment_key,
                    release_version, data, filename, correlation_id, routing_key, document_key, status
                )
                VALUES (:id, :batchId, :tenantId, :catalogKey, :templateId, :variantId, NULL, :environmentId,
                        :releaseVersion, :data::jsonb, :filename, :correlationId, :routingKey, NULL, :status)
                """,
            )

            for ((index, item) in command.items.withIndex()) {
                val requestId = GenerationRequestKey.generate()
                // Routing-key precedence: item-level wins, then batch-level default,
                // then null (emitter falls back to request id at terminal state).
                val effectiveRoutingKey = item.routingKey ?: command.batchRoutingKey
                batch.bind("id", requestId)
                    .bind("batchId", batchId)
                    .bind("tenantId", command.tenantId)
                    .bind("catalogKey", item.catalogKey)
                    .bind("templateId", item.templateId)
                    .bind("variantId", resolvedVariantIds[index])
                    .bind("environmentId", item.environmentId)
                    .bind("releaseVersion", targets[index]?.release?.version)
                    .bind("data", item.data.toString())
                    .bind("filename", item.filename)
                    .bind("correlationId", item.correlationId)
                    .bind("routingKey", effectiveRoutingKey)
                    .bind("status", RequestStatus.PENDING.name)
                    .add()
            }

            val inserted = batch.execute().sum()
            logger.info("Created batch {} with {} requests for tenant {}", batchId.value, inserted, command.tenantId)

            // Requests stay in PENDING status - the JobPoller drains them on its next poll.
            batchId
        }

        return batchId
    }

    private fun resolveDefaultVariant(tenantId: TenantKey, catalogKey: CatalogKey, templateId: TemplateKey): VariantKey {
        val variantId = jdbi.withHandle<String?, Exception> { handle ->
            handle.createQuery(
                """
                SELECT id FROM template_variants
                WHERE tenant_key = :tenantId AND template_resource_id = ${templateAtAddress("tenantId", "catalogKey", "templateId")} AND is_default = TRUE
                """,
            )
                .bind("tenantId", tenantId)
                .bind("catalogKey", catalogKey)
                .bind("templateId", templateId)
                .mapTo<String>()
                .findOne()
                .orElse(null)
        }
        return VariantKey.of(
            variantId ?: throw DefaultVariantNotFoundException(tenantId, templateId),
        )
    }
}
