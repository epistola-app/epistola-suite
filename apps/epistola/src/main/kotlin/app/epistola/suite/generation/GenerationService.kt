package app.epistola.suite.generation

import app.epistola.generation.pdf.DirectPdfRenderer
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.templates.validation.ValidationError
import app.epistola.suite.themes.ThemeStyleResolver
import app.epistola.template.model.TemplateModel
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.OutputStream

/**
 * Result of preview data validation.
 */
data class PreviewValidationResult(
    val valid: Boolean,
    val errors: List<ValidationError> = emptyList(),
)

/**
 * Service for document generation operations.
 * Wraps the generation module's DirectPdfRenderer and provides template lookup.
 */
@Service
class GenerationService(
    private val objectMapper: ObjectMapper,
    private val schemaValidator: JsonSchemaValidator,
    private val themeStyleResolver: ThemeStyleResolver,
    private val pdfRenderer: DirectPdfRenderer = DirectPdfRenderer(),
) {
    /**
     * Renders a PDF from a template model and data context.
     *
     * Theme resolution cascade:
     * 1. Variant-level theme (TemplateModel.themeId) - highest priority
     * 2. Template-level default theme (templateDefaultThemeId parameter) - fallback
     * 3. Tenant default theme (tenantDefaultThemeId parameter) - ultimate fallback
     *
     * When a theme is resolved, theme styles are merged:
     * - Theme document styles serve as defaults, template document styles override
     * - Theme block style presets are made available for blocks with stylePreset
     *
     * @param tenantId The tenant ID (required for theme lookup)
     * @param templateModel The template model (either from live editor or pre-fetched draft)
     * @param data The data context for expression evaluation
     * @param outputStream The output stream to write the PDF to
     * @param templateDefaultThemeId Optional default theme from DocumentTemplate (variant can override)
     * @param tenantDefaultThemeId Optional default theme from Tenant (ultimate fallback)
     */
    fun renderPdf(
        tenantId: TenantId,
        templateModel: TemplateModel,
        data: Map<String, Any?>,
        outputStream: OutputStream,
        templateDefaultThemeId: ThemeId? = null,
        tenantDefaultThemeId: ThemeId? = null,
    ) {
        // Resolve styles from theme (variant-level > template-level > tenant-level)
        val resolvedStyles = themeStyleResolver.resolveStyles(
            tenantId,
            templateDefaultThemeId,
            tenantDefaultThemeId,
            templateModel,
        )

        pdfRenderer.render(
            template = templateModel,
            data = data,
            outputStream = outputStream,
            blockStylePresets = resolvedStyles.blockStylePresets,
            resolvedDocumentStyles = resolvedStyles.documentStyles,
        )
    }

    /**
     * Renders a PDF without theme resolution.
     * Use this for previews where theme lookup is not needed or tenant context is unavailable.
     *
     * @param templateModel The template model
     * @param data The data context for expression evaluation
     * @param outputStream The output stream to write the PDF to
     */
    fun renderPdfWithoutTheme(
        templateModel: TemplateModel,
        data: Map<String, Any?>,
        outputStream: OutputStream,
    ) {
        pdfRenderer.render(templateModel, data, outputStream)
    }

    /**
     * Converts a Map representation of a template model to a TemplateModel instance.
     * Use this when receiving template model from the editor as JSON.
     *
     * @param templateModelMap The template model as a Map
     * @return The converted TemplateModel
     */
    fun convertTemplateModel(templateModelMap: Map<String, Any?>): TemplateModel = objectMapper.convertValue(templateModelMap, TemplateModel::class.java)

    /**
     * Validates data against the template's schema without generating a PDF.
     * Use this before streaming to catch validation errors early.
     *
     * @param tenantId The tenant ID
     * @param templateId The template ID
     * @param data The data context to validate
     * @return Validation result with any errors found
     */
    fun validatePreviewData(
        tenantId: TenantId,
        templateId: TemplateId,
        data: Map<String, Any?>,
    ): PreviewValidationResult {
        val template = GetDocumentTemplate(tenantId, templateId).query()
            ?: return PreviewValidationResult(valid = true) // No template means nothing to validate against

        if (template.dataModel == null) {
            return PreviewValidationResult(valid = true) // No schema means no validation
        }

        val dataNode = objectMapper.valueToTree<ObjectNode>(data)
        val errors = schemaValidator.validate(template.dataModel, dataNode)

        return PreviewValidationResult(
            valid = errors.isEmpty(),
            errors = errors,
        )
    }
}
