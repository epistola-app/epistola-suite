package app.epistola.suite.templates.commands.contracts

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.ContractVersion
import app.epistola.suite.templates.model.DataExamples
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Creates a draft contract version for a template ("start editing").
 * If a draft already exists, returns it (idempotent).
 * If no draft exists but a published version does, copies the latest published
 * contract's schema, dataModel, and dataExamples as defaults (command fields override).
 * After creating the draft, links all draft template versions to the new contract version.
 * Version ID is calculated as MAX(id) + 1.
 */
data class CreateContractVersion(
    val templateId: TemplateId,
    val schema: ObjectNode? = null,
    val dataModel: ObjectNode? = null,
    val dataExamples: DataExamples = DataExamples.EMPTY,
) : Command<ContractVersion?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = templateId.tenantKey
}

@Component
class CreateContractVersionHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<CreateContractVersion, ContractVersion?> {
    override fun handle(command: CreateContractVersion): ContractVersion? {
        requireCatalogEditable(command.templateId.tenantKey, command.templateId.catalogKey)
        return jdbi.inTransaction<ContractVersion?, Exception> { handle ->
            // Lock the template row to serialize contract version creation
            val templateRow = handle.createQuery(
                """
                SELECT id
                FROM document_templates
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND id = :templateKey
                FOR UPDATE
                """,
            )
                .bind("tenantKey", command.templateId.tenantKey)
                .bind("catalogKey", command.templateId.catalogKey)
                .bind("templateKey", command.templateId.key)
                .mapToMap()
                .findOne()
                .orElse(null)

            if (templateRow == null) return@inTransaction null

            // Check if a draft already exists (idempotent)
            val existingDraft = handle.createQuery(
                """
                SELECT id, tenant_key, catalog_key, template_key, schema, data_model, data_examples,
                       status, created_at, published_at, created_by
                FROM contract_versions
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey AND status = 'draft'
                """,
            )
                .bind("tenantKey", command.templateId.tenantKey)
                .bind("catalogKey", command.templateId.catalogKey)
                .bind("templateKey", command.templateId.key)
                .mapTo<ContractVersion>()
                .findOne()
                .orElse(null)

            if (existingDraft != null) return@inTransaction existingDraft

            // Calculate next version ID
            val nextVersionId = handle.createQuery(
                """
                SELECT COALESCE(MAX(id), 0) + 1
                FROM contract_versions
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND template_key = :templateKey
                """,
            )
                .bind("tenantKey", command.templateId.tenantKey)
                .bind("catalogKey", command.templateId.catalogKey)
                .bind("templateKey", command.templateId.key)
                .mapTo(Int::class.java)
                .one()

            require(nextVersionId <= VersionKey.MAX_VERSION) {
                "Maximum contract version limit (${VersionKey.MAX_VERSION}) reached"
            }

            // Load latest published to copy from (if exists)
            val latestPublished = handle.createQuery(
                """
                SELECT id, tenant_key, catalog_key, template_key, schema, data_model, data_examples,
                       status, created_at, published_at, created_by
                FROM contract_versions
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey AND status = 'published'
                ORDER BY id DESC LIMIT 1
                """,
            )
                .bind("tenantKey", command.templateId.tenantKey)
                .bind("catalogKey", command.templateId.catalogKey)
                .bind("templateKey", command.templateId.key)
                .mapTo<ContractVersion>()
                .findOne()
                .orElse(null)

            // Command fields override copied values; fall back to published values
            val effectiveSchema = command.schema ?: latestPublished?.schema
            val effectiveDataModel = command.dataModel ?: latestPublished?.dataModel
            val effectiveDataExamples = if (command.dataExamples.isNotEmpty()) command.dataExamples else (latestPublished?.dataExamples ?: DataExamples.EMPTY)

            val schemaJson = effectiveSchema?.let { objectMapper.writeValueAsString(it) }
            val dataModelJson = effectiveDataModel?.let { objectMapper.writeValueAsString(it) }
            val dataExamplesJson = objectMapper.writeValueAsString(effectiveDataExamples)

            val newDraft = handle.createQuery(
                """
                INSERT INTO contract_versions (id, tenant_key, catalog_key, template_key, schema, data_model, data_examples, status, created_at)
                VALUES (:id, :tenantKey, :catalogKey, :templateKey, :schema::jsonb, :dataModel::jsonb, :dataExamples::jsonb, 'draft', NOW())
                RETURNING id, tenant_key, catalog_key, template_key, schema, data_model, data_examples, status, created_at, published_at, created_by
                """,
            )
                .bind("id", VersionKey.of(nextVersionId))
                .bind("tenantKey", command.templateId.tenantKey)
                .bind("catalogKey", command.templateId.catalogKey)
                .bind("templateKey", command.templateId.key)
                .bind("schema", schemaJson)
                .bind("dataModel", dataModelJson)
                .bind("dataExamples", dataExamplesJson)
                .mapTo<ContractVersion>()
                .one()

            // Link all draft template versions to the new contract version
            handle.createUpdate(
                """
                UPDATE template_versions
                SET contract_version = :newVersion
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey AND status = 'draft'
                """,
            )
                .bind("tenantKey", command.templateId.tenantKey)
                .bind("catalogKey", command.templateId.catalogKey)
                .bind("templateKey", command.templateId.key)
                .bind("newVersion", nextVersionId)
                .execute()

            newDraft
        }
    }
}
