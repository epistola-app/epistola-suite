package app.epistola.suite.templates.commands

import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
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
    val tenantId: TenantKey,
    val templates: List<ImportTemplateInput>,
) : Command<List<ImportTemplateResult>>

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

    private fun importSingleTemplate(tenantId: TenantKey, input: ImportTemplateInput): ImportTemplateResult = jdbi.inTransaction<ImportTemplateResult, Exception> { handle ->
        val templateId = TemplateKey.of(input.slug)
        val defaultVariantId = VariantKey.of("${input.slug}-default")
        val dataModelJson = input.dataModel?.let { objectMapper.writeValueAsString(it) }
        val dataExamplesJson = objectMapper.writeValueAsString(input.dataExamples)

        // 1. Check if template exists
        val templateExists = handle.createQuery(
            """
                SELECT COUNT(*) > 0
                FROM document_templates
                WHERE id = :id AND tenant_key = :tenantId
                """,
        )
            .bind("id", templateId)
            .bind("tenantId", tenantId)
            .mapTo<Boolean>()
            .one()

        // 1. Upsert template
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
                .bind("tenantId", tenantId)
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
                .bind("tenantId", tenantId)
                .bind("name", input.name)
                .bind("dataModel", dataModelJson)
                .bind("dataExamples", dataExamplesJson)
                .execute()
            ImportStatus.UPDATED
        }

        // Upsert default variant
        handle.createUpdate(
            """
                INSERT INTO template_variants (id, tenant_key, template_key, attributes, is_default, created_at, last_modified)
                VALUES (:id, :tenantId, :templateId, '{}'::jsonb, TRUE, NOW(), NOW())
                ON CONFLICT (tenant_key, id) DO UPDATE SET template_key = :templateId, last_modified = NOW()
                """,
        )
            .bind("id", defaultVariantId)
            .bind("tenantId", tenantId)
            .bind("templateId", templateId)
            .execute()

        // Upsert draft for default variant (unless an explicit variant overrides it)
        val hasExplicitDefault = input.variants.any { it.id == defaultVariantId.value }
        if (!hasExplicitDefault) {
            upsertDraft(handle, tenantId, defaultVariantId, input.templateModel)
        }

        // 2. Process explicit variants
        val allVariantIds = mutableSetOf(defaultVariantId)
        for (variantInput in input.variants) {
            val variantId = VariantKey.of(variantInput.id)
            allVariantIds.add(variantId)

            val attributesJson = objectMapper.writeValueAsString(variantInput.attributes)

            handle.createUpdate(
                """
                    INSERT INTO template_variants (id, tenant_key, template_key, title, attributes, is_default, created_at, last_modified)
                    VALUES (:id, :tenantId, :templateId, :title, :attributes::jsonb, FALSE, NOW(), NOW())
                    ON CONFLICT (tenant_key, id) DO UPDATE
                    SET title = :title, attributes = :attributes::jsonb, template_key = :templateId, last_modified = NOW()
                    """,
            )
                .bind("id", variantId)
                .bind("tenantId", tenantId)
                .bind("templateId", templateId)
                .bind("title", variantInput.title)
                .bind("attributes", attributesJson)
                .execute()

            // Upsert the draft with the variant-specific or top-level templateModel
            val variantTemplateModel = variantInput.templateModel ?: input.templateModel
            upsertDraft(handle, tenantId, variantId, variantTemplateModel)
        }

        // 3. Publish to environments
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
                .bind("tenantId", tenantId)
                .mapTo<Boolean>()
                .one()

            if (!environmentExists) {
                logger.warn("Skipping publish for template '${input.slug}': environment '$envSlug' does not exist")
                continue
            }

            for (variantId in allVariantIds) {
                publishDraft(handle, tenantId, variantId, environmentId)
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
    private fun upsertDraft(handle: Handle, tenantId: TenantKey, variantId: VariantKey, templateModel: TemplateDocument) {
        val templateModelJson = objectMapper.writeValueAsString(templateModel)

        val updated = handle.createUpdate(
            """
            UPDATE template_versions
            SET template_model = :templateModel::jsonb
            WHERE tenant_key = :tenantId AND variant_key = :variantId AND status = 'draft'
            """,
        )
            .bind("tenantId", tenantId)
            .bind("variantId", variantId)
            .bind("templateModel", templateModelJson)
            .execute()

        if (updated == 0) {
            val nextVersionId = handle.createQuery(
                """
                SELECT COALESCE(MAX(id), 0) + 1
                FROM template_versions
                WHERE tenant_key = :tenantId AND variant_key = :variantId
                """,
            )
                .bind("tenantId", tenantId)
                .bind("variantId", variantId)
                .mapTo(Int::class.java)
                .one()

            handle.createUpdate(
                """
                INSERT INTO template_versions (id, tenant_key, variant_key, template_model, status, created_at)
                VALUES (:id, :tenantId, :variantId, :templateModel::jsonb, 'draft', NOW())
                """,
            )
                .bind("id", VersionKey.of(nextVersionId))
                .bind("tenantId", tenantId)
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
    private fun publishDraft(handle: Handle, tenantId: TenantKey, variantId: VariantKey, environmentId: EnvironmentKey) {
        // Find the draft version
        val draftVersionId = handle.createQuery(
            """
            SELECT id
            FROM template_versions
            WHERE tenant_key = :tenantId AND variant_key = :variantId AND status = 'draft'
            """,
        )
            .bind("tenantId", tenantId)
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
            WHERE tenant_key = :tenantId AND variant_key = :variantId AND id = :versionId
            """,
        )
            .bind("tenantId", tenantId)
            .bind("variantId", variantId)
            .bind("versionId", versionId)
            .execute()

        // Upsert activation
        handle.createUpdate(
            """
            INSERT INTO environment_activations (tenant_key, environment_key, variant_key, version_key, activated_at)
            VALUES (:tenantId, :environmentId, :variantId, :versionId, NOW())
            ON CONFLICT (tenant_key, environment_key, variant_key)
            DO UPDATE SET version_key = :versionId, activated_at = NOW()
            """,
        )
            .bind("tenantId", tenantId)
            .bind("environmentId", environmentId)
            .bind("variantId", variantId)
            .bind("versionId", versionId)
            .execute()

        // Auto-create a new draft so the variant remains editable
        val nextVersionId = handle.createQuery(
            """
            SELECT COALESCE(MAX(id), 0) + 1
            FROM template_versions
            WHERE tenant_key = :tenantId AND variant_key = :variantId
            """,
        )
            .bind("tenantId", tenantId)
            .bind("variantId", variantId)
            .mapTo(Int::class.java)
            .one()

        handle.createUpdate(
            """
            INSERT INTO template_versions (id, tenant_key, variant_key, template_model, status, created_at)
            VALUES (:id, :tenantId, :variantId,
                    (SELECT template_model FROM template_versions WHERE tenant_key = :tenantId AND variant_key = :variantId AND id = :publishedId),
                    'draft', NOW())
            """,
        )
            .bind("id", VersionKey.of(nextVersionId))
            .bind("tenantId", tenantId)
            .bind("variantId", variantId)
            .bind("publishedId", versionId)
            .execute()
    }
}
