// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.ready.ReadyMarks
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.stencils.StencilNodeKeys
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.services.TemplateDocumentPreparation
import app.epistola.suite.templates.templateAtAddress
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.templates.validation.TemplateDocumentValidator
import app.epistola.suite.templates.validation.requireValidDataContractSchema
import app.epistola.suite.themes.themeAtAddress
import app.epistola.suite.validation.FieldLimits.MAX_NAME_COLUMN_LENGTH
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

// ── Command ─────────────────────────────────────────────────────────────────

data class ImportTemplates(
    val tenantId: TenantId,
    val catalogKey: CatalogKey = CatalogKey.DEFAULT,
    val templates: List<ImportTemplateInput>,
) : Command<List<ImportTemplateResult>>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = tenantId.key

    init {
        // Column ceilings (#692): document_templates.name and template_variants.title are
        // VARCHAR(255). Validate the RESOLVED title (a blank import falls back to the variant
        // id), so what actually gets written is bounded. One template per command in the
        // catalog fan-out, so a rejection isolates to that resource.
        templates.forEach { template ->
            validate("name", template.name.length <= MAX_NAME_COLUMN_LENGTH) { "Name must be $MAX_NAME_COLUMN_LENGTH characters or less" }
            template.variants.forEach { variant ->
                validate("title", variant.resolvedTitle().length <= MAX_NAME_COLUMN_LENGTH) { "Title must be $MAX_NAME_COLUMN_LENGTH characters or less" }
            }
        }
    }
}

data class ImportTemplateInput(
    val slug: String,
    val name: String,
    val version: String,
    val themeId: String? = null,
    val themeCatalogKey: String? = null,
    val pdfaEnabled: Boolean = true,
    val dataModel: ObjectNode?,
    val dataExamples: List<DataExample>,
    val templateModel: TemplateDocument,
    val variants: List<ImportVariantInput>,
)

data class ImportVariantInput(
    val id: String,
    val title: String?,
    val attributes: Map<String, String>,
    val templateModel: TemplateDocument?,
    val isDefault: Boolean = false,
)

/**
 * The title actually written for an imported variant: a title-less or blank
 * import falls back to the variant id (#631). Shared by [ImportTemplates] length
 * validation and the handler bind so the two never drift.
 */
internal fun ImportVariantInput.resolvedTitle(): String = title?.takeIf { it.isNotBlank() } ?: id

data class ImportTemplateResult(
    val slug: String,
    val status: ImportStatus,
    val version: String,
    val errorMessage: String?,
)

enum class ImportStatus {
    CREATED,
    UPDATED,
    UNCHANGED,
    FAILED,
}

// ── Handler ─────────────────────────────────────────────────────────────────

@Component
class ImportTemplatesHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val jsonSchemaValidator: JsonSchemaValidator,
    private val templateDocumentValidator: TemplateDocumentValidator,
    private val templateDocumentPreparation: TemplateDocumentPreparation,
    private val readyMarks: ReadyMarks,
) : CommandHandler<ImportTemplates, List<ImportTemplateResult>> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: ImportTemplates): List<ImportTemplateResult> = command.templates.map { input ->
        try {
            validateTemplateModels(input)
            importSingleTemplate(command.tenantId, command.catalogKey, input)
        } catch (e: Exception) {
            logger.error("Failed to import template '${input.slug}': ${e.message}", e)
            ImportTemplateResult(
                slug = input.slug,
                status = ImportStatus.FAILED,
                version = input.version,
                errorMessage = e.message ?: "Unknown error",
            )
        }
    }

    private fun validateTemplateModels(input: ImportTemplateInput) {
        templateDocumentValidator.validateTemplatePublishable(input.templateModel)
        input.variants.mapNotNull { it.templateModel }.forEach(templateDocumentValidator::validateTemplate)
    }

    private fun importSingleTemplate(tenantId: TenantId, catalogKey: CatalogKey, input: ImportTemplateInput): ImportTemplateResult = jdbi.inTransaction<ImportTemplateResult, Exception> { handle ->
        val templateId = TemplateKey.of(input.slug)
        val auditUser = currentUserIdOrNull()?.value
        val dataModelJson = input.dataModel?.let { objectMapper.writeValueAsString(it) }
        val dataExamplesJson = objectMapper.writeValueAsString(input.dataExamples)

        // Validate property names in the schema
        if (input.dataModel != null) {
            jsonSchemaValidator.requireValidDataContractSchema(input.dataModel)
            val invalidNames = jsonSchemaValidator.validatePropertyNames(input.dataModel)
            if (invalidNames.isNotEmpty()) {
                error("Template '${input.slug}': invalid property names in schema: ${invalidNames.joinToString()}")
            }
        }

        // Validate: exactly one variant must be marked as default
        val defaultCount = input.variants.count { it.isDefault }
        require(input.variants.isNotEmpty()) { "Template '${input.slug}': at least one variant is required" }
        require(defaultCount == 1) { "Template '${input.slug}': exactly one variant must have isDefault=true, found $defaultCount" }

        // 1. Check if template exists
        val templateExists = handle.createQuery(
            """
                SELECT COUNT(*) > 0
                FROM document_templates
                WHERE id = :id AND tenant_key = :tenantId AND version_id = ${versionAt("tenantId", "catalogKey")}
                """,
        )
            .bind("id", templateId)
            .bind("tenantId", tenantId.key)
            .bind("catalogKey", catalogKey)
            .mapTo<Boolean>()
            .one()

        // 2. Upsert template
        val status: ImportStatus = if (!templateExists) {
            handle.createUpdate(
                """
                    INSERT INTO document_templates (id, tenant_key, catalog_id, version_id, name, theme_resource_id, pdfa_enabled, created_at, updated_at, created_by, updated_by)
                    VALUES (:id, :tenantId, ${catalogAt("tenantId", "catalogKey")}, ${versionAt("tenantId", "catalogKey")}, :name, ${themeAtAddress("tenantId", "themeCatalogKey", "themeKey")}, :pdfaEnabled, NOW(), NOW(), :createdBy, :updatedBy)
                    ON CONFLICT (tenant_key, version_id, id) DO UPDATE
                    SET name = :name, theme_resource_id = EXCLUDED.theme_resource_id,
                        pdfa_enabled = :pdfaEnabled, updated_at = NOW(), updated_by = :updatedBy
                    """,
            )
                .bind("id", templateId)
                .bind("tenantId", tenantId.key)
                .bind("catalogKey", catalogKey)
                .bind("name", input.name)
                .bind("themeKey", input.themeId)
                .bind("themeCatalogKey", input.themeCatalogKey)
                .bind("pdfaEnabled", input.pdfaEnabled)
                .bind("createdBy", auditUser).bind("updatedBy", auditUser)
                .execute()
            ImportStatus.CREATED
        } else {
            handle.createUpdate(
                """
                    UPDATE document_templates
                    SET name = :name, theme_resource_id = ${themeAtAddress("tenantId", "themeCatalogKey", "themeKey")},
                        pdfa_enabled = :pdfaEnabled, updated_at = NOW(), updated_by = :updatedBy
                    WHERE id = :id AND tenant_key = :tenantId AND version_id = ${versionAt("tenantId", "catalogKey")}
                    """,
            )
                .bind("id", templateId)
                .bind("tenantId", tenantId.key)
                .bind("catalogKey", catalogKey)
                .bind("name", input.name)
                .bind("themeKey", input.themeId)
                .bind("themeCatalogKey", input.themeCatalogKey)
                .bind("pdfaEnabled", input.pdfaEnabled)
                .bind("createdBy", auditUser).bind("updatedBy", auditUser)
                .execute()
            ImportStatus.UPDATED
        }

        // 2b. The data contract: import supersedes local edits.
        handle.createUpdate(
            """
                UPDATE document_templates SET data_model = CAST(:dataModel AS JSONB), data_examples = CAST(:dataExamples AS JSONB)
                WHERE tenant_key = :tenantId AND version_id = ${versionAt("tenantId", "catalogKey")} AND resource_id = ${templateAtAddress("tenantId", "catalogKey", "templateId")}
                """,
        )
            .bind("tenantId", tenantId.key)
            .bind("catalogKey", catalogKey)
            .bind("templateId", templateId)
            .bind("dataModel", dataModelJson)
            .bind("dataExamples", dataExamplesJson)
            .execute()

        // 3. Clear existing default flag to avoid unique partial index conflict during upserts
        handle.createUpdate(
            """
                UPDATE template_variants SET is_default = FALSE
                WHERE tenant_key = :tenantId AND version_id = ${versionAt("tenantId", "catalogKey")} AND template_resource_id = ${templateAtAddress("tenantId", "catalogKey", "templateId")} AND is_default = TRUE
                """,
        )
            .bind("tenantId", tenantId.key)
            .bind("catalogKey", catalogKey)
            .bind("templateId", templateId)
            .execute()

        // 4. Upsert each variant with caller's is_default value
        val importedVariantIds = mutableSetOf<VariantKey>()
        for (variantInput in input.variants) {
            val variantId = VariantKey.of(variantInput.id)
            importedVariantIds.add(variantId)

            val attributesJson = objectMapper.writeValueAsString(variantInput.attributes)
            // The variant's own model, else the template's; import supersedes local edits.
            val prepared = templateDocumentPreparation.prepare(variantInput.templateModel ?: input.templateModel, catalogKey)

            handle.createUpdate(
                """
                    INSERT INTO template_variants (id, tenant_key, version_id, template_resource_id, title, attributes, is_default, template_model, referenced_paths,
                                                   created_at, updated_at, created_by, updated_by)
                    VALUES (:id, :tenantId, ${versionAt("tenantId", "catalogKey")}, ${templateAtAddress("tenantId", "catalogKey", "templateId")}, :title, :attributes::jsonb, :isDefault,
                            :templateModel::jsonb, :referencedPaths::jsonb, NOW(), NOW(), :createdBy, :updatedBy)
                    ON CONFLICT (tenant_key, version_id, template_resource_id, id) DO UPDATE
                    SET title = :title, attributes = :attributes::jsonb, is_default = :isDefault,
                        template_model = :templateModel::jsonb, referenced_paths = :referencedPaths::jsonb, updated_at = NOW(), updated_by = :updatedBy
                    """,
            )
                .bind("templateModel", prepared.templateModelJson)
                .bind("referencedPaths", prepared.referencedPathsJson)
                .bind("id", variantId)
                .bind("tenantId", tenantId.key)
                .bind("catalogKey", catalogKey)
                .bind("templateId", templateId)
                // Title is required (#631); default a title-less import to the variant slug.
                .bind("title", variantInput.resolvedTitle())
                .bind("attributes", attributesJson)
                .bind("isDefault", variantInput.isDefault)
                .bind("createdBy", auditUser).bind("updatedBy", auditUser)
                .execute()
        }

        // 5. Delete orphan variants not in the import (CASCADE cleans up versions + activations)
        handle.createUpdate(
            """
                DELETE FROM template_variants
                WHERE tenant_key = :tenantId AND version_id = ${versionAt("tenantId", "catalogKey")} AND template_resource_id = ${templateAtAddress("tenantId", "catalogKey", "templateId")}
                  AND id NOT IN (<variantIds>)
                """,
        )
            .bind("tenantId", tenantId.key)
            .bind("catalogKey", catalogKey)
            .bind("templateId", templateId)
            .bindList("variantIds", importedVariantIds.toList())
            .execute()

        // What a catalog brings is released content: imported, it is ready as it is.
        handle.createQuery("SELECT ${templateAtAddress("tenantId", "catalogKey", "templateId")}")
            .bind("tenantId", tenantId.key)
            .bind("catalogKey", catalogKey)
            .bind("templateId", templateId)
            .mapTo(java.util.UUID::class.java)
            .one()
            .let { readyMarks.mark(handle, tenantId.key, it) }

        ImportTemplateResult(
            slug = input.slug,
            status = status,
            version = input.version,
            errorMessage = null,
        )
    }
}
