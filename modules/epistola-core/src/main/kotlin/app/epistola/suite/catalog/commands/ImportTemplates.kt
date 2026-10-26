// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.ready.ReadyMarks
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
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
import app.epistola.suite.templates.StoredTemplate
import app.epistola.suite.templates.StoredVariant
import app.epistola.suite.templates.TemplateStore
import app.epistola.suite.templates.fitsTemplateDescription
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.services.TemplateDocumentPreparation
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.templates.validation.TemplateDocumentValidator
import app.epistola.suite.templates.validation.requireValidDataContractSchema
import app.epistola.suite.themes.themeAtAddress
import app.epistola.suite.validation.FieldLimits.MAX_NAME_COLUMN_LENGTH
import app.epistola.suite.validation.FieldLimits.MAX_TEMPLATE_DESCRIPTION_LENGTH
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
    val catalog: CatalogId,
    val templates: List<ImportTemplateInput>,
) : Command<List<ImportTemplateResult>>,
    RequiresPermission {
    val tenantId: TenantId get() = catalog.tenantId
    val catalogKey: CatalogKey get() = catalog.key
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = tenantId.key

    init {
        // Column ceilings (#692): a template's name and a variant's title were
        // VARCHAR(255). Validate the RESOLVED title (a blank import falls back to the variant
        // id), so what actually gets written is bounded. One template per command in the
        // catalog fan-out, so a rejection isolates to that resource.
        templates.forEach { template ->
            validate("name", template.name.length <= MAX_NAME_COLUMN_LENGTH) { "Name must be $MAX_NAME_COLUMN_LENGTH characters or less" }
            validate("description", template.description.fitsTemplateDescription()) {
                "Description must be $MAX_TEMPLATE_DESCRIPTION_LENGTH characters or less"
            }
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
    /** The catalog of [themeId], by its address (ADR 0034, section 9). */
    val themeCatalog: app.epistola.suite.common.ids.CatalogId? = null,
    val pdfaEnabled: Boolean = true,
    val dataModel: ObjectNode?,
    val dataExamples: List<DataExample>,
    val templateModel: TemplateDocument,
    val variants: List<ImportVariantInput>,
    /** Plain-text notes for the template's authors (wire v8 `TemplateResource.description`); null when it has none. */
    val description: String? = null,
)

data class ImportVariantInput(
    val id: String,
    val title: String?,
    val attributes: Map<String, String>,
    val templateModel: TemplateDocument?,
    val isDefault: Boolean = false,
    /**
     * Producer-specific data by reverse-DNS namespace (wire v8 `VariantEntry.metadata`), kept as it
     * came and written back on export; null or empty when the variant has none.
     */
    val metadata: Map<String, Map<String, Any?>>? = null,
    /** The variant's description (wire v8 `VariantEntry.description`); null when it has none. */
    val description: String? = null,
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
    private val templates: TemplateStore,
) : CommandHandler<ImportTemplates, List<ImportTemplateResult>> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: ImportTemplates): List<ImportTemplateResult> = command.templates.map { input ->
        try {
            validateTemplateModels(input)
            importSingleTemplate(command.catalog, input)
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

    private fun importSingleTemplate(catalog: CatalogId, input: ImportTemplateInput): ImportTemplateResult = jdbi.inTransaction<ImportTemplateResult, Exception> { handle ->
        val templateId = TemplateId(TemplateKey.of(input.slug), catalog)
        val auditUser = currentUserIdOrNull()?.value

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

        val loaded = templates.get(handle, templateId)
        val existing = loaded?.template
        // Import supersedes local edits; a variant's description, which the wire form does not carry
        // yet, stays with the variant that keeps its key.
        val template = StoredTemplate(
            key = input.slug,
            name = input.name,
            description = input.description,
            themeKey = input.themeId,
            // By alias (ADR 0034): a theme of the template's own catalog is named without one.
            themeCatalogKey = input.themeCatalog?.takeUnless { it == catalog }?.key?.value.takeIf { input.themeId != null },
            pdfaEnabled = input.pdfaEnabled,
            dataModel = input.dataModel?.let { objectMapper.convertValue(it, MAP) },
            dataExamples = TemplateStore.entriesOf(input.dataExamples, objectMapper),
            variants = input.variants.map { variantInput ->
                // The variant's own model, else the template's.
                val prepared = templateDocumentPreparation.prepare(variantInput.templateModel ?: input.templateModel, catalog.key)
                StoredVariant(
                    key = variantInput.id,
                    // Title is required (#631); default a title-less import to the variant slug.
                    title = variantInput.resolvedTitle(),
                    description = variantInput.description?.takeIf { it.isNotBlank() },
                    attributes = variantInput.attributes,
                    isDefault = variantInput.isDefault,
                    // Empty metadata is no metadata: the wire forbids `{}`.
                    metadata = variantInput.metadata?.takeIf { it.isNotEmpty() },
                    model = templates.storedModel(prepared.templateModelJson),
                )
            },
        )
        val status = if (existing == null) {
            templates.create(handle, catalog, template, auditUser)
            ImportStatus.CREATED
        } else {
            checkNotNull(templates.save(handle, catalog, checkNotNull(loaded), template, auditUser)) { "Template ${input.slug} went while it was imported" }
            ImportStatus.UPDATED
        }

        // What a catalog brings is released content: imported, it is ready as it is.
        readyMarks.mark(handle, catalog.tenantId.key, checkNotNull(templates.get(handle, templateId)).resourceId)

        ImportTemplateResult(
            slug = input.slug,
            status = status,
            version = input.version,
            errorMessage = null,
        )
    }

    private companion object {
        val MAP = object : tools.jackson.core.type.TypeReference<Map<String, Any?>>() {}
    }
}
