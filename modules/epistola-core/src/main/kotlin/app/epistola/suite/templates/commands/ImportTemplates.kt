package app.epistola.suite.templates.commands

import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.model.TemplateDocument
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
    val templates: List<ImportTemplateInput>,
) : Command<List<ImportTemplateResult>>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = tenantId.key
}

data class ImportTemplateInput(
    val slug: String,
    val name: String,
    val version: String,
    val dataModel: ObjectNode?,
    val dataExamples: List<DataExample>,
    val templateModel: TemplateDocument,
    val variants: List<ImportVariantInput>,
    val publishTo: List<String>,
)

data class ImportVariantInput(
    val id: String,
    val title: String?,
    val attributes: Map<String, String>,
    val templateModel: TemplateDocument?,
    val isDefault: Boolean = false,
)

data class ImportTemplateResult(
    val slug: String,
    val status: ImportStatus,
    val version: String,
    val publishedTo: List<String>,
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
) : CommandHandler<ImportTemplates, List<ImportTemplateResult>> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: ImportTemplates): List<ImportTemplateResult> = command.templates.map { input ->
        try {
            importSingleTemplate(command.tenantId, input)
        } catch (e: Exception) {
            logger.error("Failed to import template '${input.slug}': ${e.message}", e)
            ImportTemplateResult(
                slug = input.slug,
                status = ImportStatus.FAILED,
                version = input.version,
                publishedTo = emptyList(),
                errorMessage = e.message ?: "Unknown error",
            )
        }
    }

    private fun importSingleTemplate(tenantId: TenantId, input: ImportTemplateInput): ImportTemplateResult = jdbi.inTransaction<ImportTemplateResult, Exception> { handle ->
        val templateId = TemplateKey.of(input.slug)
        val dataModelJson = input.dataModel?.let { objectMapper.writeValueAsString(it) }
        val dataExamplesJson = objectMapper.writeValueAsString(input.dataExamples)

        // Validate: exactly one variant must be marked as default
        val defaultCount = input.variants.count { it.isDefault }
        require(input.variants.isNotEmpty()) { "Template '${input.slug}': at least one variant is required" }
        require(defaultCount == 1) { "Template '${input.slug}': exactly one variant must have isDefault=true, found $defaultCount" }

        // 1. Check if template exists
        val templateExists = handle.createQuery(
            """
                SELECT COUNT(*) > 0
                FROM document_templates
                WHERE id = :id AND tenant_key = :tenantId
                """,
        )
            .bind("id", templateId)
            .bind("tenantId", tenantId.key)
            .mapTo<Boolean>()
            .one()

        // 2. Upsert template
        val status: ImportStatus = if (!templateExists) {
            handle.createUpdate(
                """
                    INSERT INTO document_templates (id, tenant_key, name, theme_key, schema, data_model, data_examples, pdfa_enabled, created_at, last_modified)
                    VALUES (:id, :tenantId, :name, NULL, NULL, :dataModel::jsonb, :dataExamples::jsonb, FALSE, NOW(), NOW())
                    ON CONFLICT (tenant_key, id) DO UPDATE
                    SET name = :name, data_model = :dataModel::jsonb, data_examples = :dataExamples::jsonb, last_modified = NOW()
                    """,
            )
                .bind("id", templateId)
                .bind("tenantId", tenantId.key)
                .bind("name", input.name)
                .bind("dataModel", dataModelJson)
                .bind("dataExamples", dataExamplesJson)
                .execute()
            ImportStatus.CREATED
        } else {
            handle.createUpdate(
                """
                    UPDATE document_templates
                    SET name = :name, data_model = :dataModel::jsonb, data_examples = :dataExamples::jsonb, last_modified = NOW()
                    WHERE id = :id AND tenant_key = :tenantId
                    """,
            )
                .bind("id", templateId)
                .bind("tenantId", tenantId.key)
                .bind("name", input.name)
                .bind("dataModel", dataModelJson)
                .bind("dataExamples", dataExamplesJson)
                .execute()
            ImportStatus.UPDATED
        }

        // 3. Clear existing default flag to avoid unique partial index conflict during upserts
        handle.createUpdate(
            """
                UPDATE template_variants SET is_default = FALSE
                WHERE tenant_key = :tenantId AND template_key = :templateId AND is_default = TRUE
                """,
        )
            .bind("tenantId", tenantId.key)
            .bind("templateId", templateId)
            .execute()

        // 4. Upsert each variant with caller's is_default value
        val importedVariantIds = mutableSetOf<VariantKey>()
        for (variantInput in input.variants) {
            val variantId = VariantKey.of(variantInput.id)
            importedVariantIds.add(variantId)

            val attributesJson = objectMapper.writeValueAsString(variantInput.attributes)

            handle.createUpdate(
                """
                    INSERT INTO template_variants (id, tenant_key, template_key, title, attributes, is_default, created_at, last_modified)
                    VALUES (:id, :tenantId, :templateId, :title, :attributes::jsonb, :isDefault, NOW(), NOW())
                    ON CONFLICT (tenant_key, template_key, id) DO UPDATE
                    SET title = :title, attributes = :attributes::jsonb, is_default = :isDefault, last_modified = NOW()
                    """,
            )
                .bind("id", variantId)
                .bind("tenantId", tenantId.key)
                .bind("templateId", templateId)
                .bind("title", variantInput.title)
                .bind("attributes", attributesJson)
                .bind("isDefault", variantInput.isDefault)
                .execute()

            // Upsert the draft with the variant-specific or top-level templateModel
            val variantTemplateModel = variantInput.templateModel ?: input.templateModel
            upsertDraft(handle, tenantId, templateId, variantId, variantTemplateModel)
        }

        // 5. Delete orphan variants not in the import (CASCADE cleans up versions + activations)
        handle.createUpdate(
            """
                DELETE FROM template_variants
                WHERE tenant_key = :tenantId AND template_key = :templateId
                  AND id NOT IN (<variantIds>)
                """,
        )
            .bind("tenantId", tenantId.key)
            .bind("templateId", templateId)
            .bindList("variantIds", importedVariantIds.toList())
            .execute()

        // 6. Publish to environments (only imported variants)
        val publishedTo = mutableListOf<String>()
        for (envSlug in input.publishTo) {
            val environmentId = EnvironmentKey.of(envSlug)

            val environmentExists = handle.createQuery(
                """
                    SELECT COUNT(*) > 0
                    FROM environments
                    WHERE id = :environmentId AND tenant_key = :tenantId
                    """,
            )
                .bind("environmentId", environmentId)
                .bind("tenantId", tenantId.key)
                .mapTo<Boolean>()
                .one()

            if (!environmentExists) {
                logger.warn("Skipping publish for template '${input.slug}': environment '$envSlug' does not exist")
                continue
            }

            for (variantId in importedVariantIds) {
                publishDraft(handle, tenantId, templateId, variantId, environmentId)
            }
            publishedTo.add(envSlug)
        }

        ImportTemplateResult(
            slug = input.slug,
            status = status,
            version = input.version,
            publishedTo = publishedTo,
            errorMessage = null,
        )
    }

    /**
     * Upserts a draft version for the given variant.
     * Updates the existing draft if one exists, otherwise creates a new one
     * with the next available version ID.
     */
    private fun upsertDraft(handle: Handle, tenantId: TenantId, templateId: TemplateKey, variantId: VariantKey, templateModel: TemplateDocument) {
        val templateModelJson = objectMapper.writeValueAsString(templateModel)

        val updated = handle.createUpdate(
            """
            UPDATE template_versions
            SET template_model = :templateModel::jsonb
            WHERE tenant_key = :tenantId AND template_key = :templateId AND variant_key = :variantId AND status = 'draft'
            """,
        )
            .bind("tenantId", tenantId.key)
            .bind("templateId", templateId)
            .bind("variantId", variantId)
            .bind("templateModel", templateModelJson)
            .execute()

        if (updated == 0) {
            val nextVersionId = handle.createQuery(
                """
                SELECT COALESCE(MAX(id), 0) + 1
                FROM template_versions
                WHERE tenant_key = :tenantId AND template_key = :templateId AND variant_key = :variantId
                """,
            )
                .bind("tenantId", tenantId.key)
                .bind("templateId", templateId)
                .bind("variantId", variantId)
                .mapTo(Int::class.java)
                .one()

            handle.createUpdate(
                """
                INSERT INTO template_versions (id, tenant_key, template_key, variant_key, template_model, status, created_at)
                VALUES (:id, :tenantId, :templateId, :variantId, :templateModel::jsonb, 'draft', NOW())
                """,
            )
                .bind("id", VersionKey.of(nextVersionId))
                .bind("tenantId", tenantId.key)
                .bind("templateId", templateId)
                .bind("variantId", variantId)
                .bind("templateModel", templateModelJson)
                .execute()
        }
    }

    /**
     * Publishes the current draft of a variant to an environment.
     * Freezes the draft (status -> published), upserts the activation,
     * and auto-creates a new draft so the variant remains editable.
     */
    private fun publishDraft(handle: Handle, tenantId: TenantId, templateId: TemplateKey, variantId: VariantKey, environmentId: EnvironmentKey) {
        // Find the draft version
        val draftVersionId = handle.createQuery(
            """
            SELECT id
            FROM template_versions
            WHERE tenant_key = :tenantId AND template_key = :templateId AND variant_key = :variantId AND status = 'draft'
            """,
        )
            .bind("tenantId", tenantId.key)
            .bind("templateId", templateId)
            .bind("variantId", variantId)
            .mapTo(Int::class.java)
            .findOne()
            .orElse(null) ?: return

        val versionId = VersionKey.of(draftVersionId)

        // Freeze the draft
        handle.createUpdate(
            """
            UPDATE template_versions
            SET status = 'published', published_at = NOW()
            WHERE tenant_key = :tenantId AND template_key = :templateId AND variant_key = :variantId AND id = :versionId
            """,
        )
            .bind("tenantId", tenantId.key)
            .bind("templateId", templateId)
            .bind("variantId", variantId)
            .bind("versionId", versionId)
            .execute()

        // Upsert activation
        handle.createUpdate(
            """
            INSERT INTO environment_activations (tenant_key, environment_key, template_key, variant_key, version_key, activated_at)
            VALUES (:tenantId, :environmentId, :templateId, :variantId, :versionId, NOW())
            ON CONFLICT (tenant_key, environment_key, template_key, variant_key)
            DO UPDATE SET version_key = :versionId, activated_at = NOW()
            """,
        )
            .bind("tenantId", tenantId.key)
            .bind("environmentId", environmentId)
            .bind("templateId", templateId)
            .bind("variantId", variantId)
            .bind("versionId", versionId)
            .execute()

        // Auto-create a new draft so the variant remains editable
        val nextVersionId = handle.createQuery(
            """
            SELECT COALESCE(MAX(id), 0) + 1
            FROM template_versions
            WHERE tenant_key = :tenantId AND template_key = :templateId AND variant_key = :variantId
            """,
        )
            .bind("tenantId", tenantId.key)
            .bind("templateId", templateId)
            .bind("variantId", variantId)
            .mapTo(Int::class.java)
            .one()

        handle.createUpdate(
            """
            INSERT INTO template_versions (id, tenant_key, template_key, variant_key, template_model, status, created_at)
            VALUES (:id, :tenantId, :templateId, :variantId,
                    (SELECT template_model FROM template_versions WHERE tenant_key = :tenantId AND template_key = :templateId AND variant_key = :variantId AND id = :publishedId),
                    'draft', NOW())
            """,
        )
            .bind("id", VersionKey.of(nextVersionId))
            .bind("tenantId", tenantId.key)
            .bind("templateId", templateId)
            .bind("variantId", variantId)
            .bind("publishedId", versionId)
            .execute()
    }
}
