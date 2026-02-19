package app.epistola.suite.generation

import app.epistola.generation.pdf.DirectPdfRenderer
import app.epistola.generation.pdf.PdfMetadata
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.templates.validation.ValidationError
import app.epistola.suite.themes.ThemeStyleResolver
import app.epistola.template.model.TemplateDocument
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
     * Renders a PDF from a template document and data context.
     *
     * Theme resolution cascade:
     * 1. Variant-level theme (TemplateDocument.themeRef override) - highest priority
     * 2. Template-level default theme (templateDefaultThemeId parameter) - fallback
     * 3. Tenant default theme (tenantDefaultThemeId parameter) - ultimate fallback
     *
     * When a theme is resolved, theme styles are merged:
     * - Theme document styles serve as defaults, template document styles override
     * - Theme block style presets are made available for blocks with stylePreset
     *
     * @param tenantId The tenant ID (required for theme lookup)
     * @param templateModel The template document (either from live editor or pre-fetched draft)
     * @param data The data context for expression evaluation
     * @param outputStream The output stream to write the PDF to
     * @param templateDefaultThemeId Optional default theme from DocumentTemplate (variant can override)
     * @param tenantDefaultThemeId Optional default theme from Tenant (ultimate fallback)
     */
    fun renderPdf(
        tenantId: TenantId,
        templateModel: TemplateDocument,
        data: Map<String, Any?>,
        outputStream: OutputStream,
        templateDefaultThemeId: ThemeId? = null,
        tenantDefaultThemeId: ThemeId? = null,
        metadata: PdfMetadata = PdfMetadata(),
        pdfaCompliant: Boolean = false,
    ) {
        // Resolve styles from theme (variant-level > template-level > tenant-level)
        val resolvedStyles = themeStyleResolver.resolveStyles(
            tenantId,
            templateDefaultThemeId,
            tenantDefaultThemeId,
            templateModel,
        )

        pdfRenderer.render(
            document = templateModel,
            data = data,
            outputStream = outputStream,
            blockStylePresets = resolvedStyles.blockStylePresets.mapValues { (_, preset) -> preset.styles },
            resolvedDocumentStyles = resolvedStyles.documentStyles,
            metadata = metadata,
            pdfaCompliant = pdfaCompliant,
        )
    }

    /**
     * Renders a PDF without theme resolution.
     * Use this for previews where theme lookup is not needed or tenant context is unavailable.
     *
     * @param templateModel The template document
     * @param data The data context for expression evaluation
     * @param outputStream The output stream to write the PDF to
     */
    fun renderPdfWithoutTheme(
        templateModel: TemplateDocument,
        data: Map<String, Any?>,
        outputStream: OutputStream,
        metadata: PdfMetadata = PdfMetadata(),
    ) {
        pdfRenderer.render(document = templateModel, data = data, outputStream = outputStream, metadata = metadata)
    }

    /**
     * Converts a Map representation of a template document to a TemplateDocument instance.
     * Use this when receiving template document from the editor as JSON.
     *
     * @param templateModelMap The template document as a Map
     * @return The converted TemplateDocument
     */
    fun convertTemplateModel(templateModelMap: Map<String, Any?>): TemplateDocument = objectMapper.convertValue(templateModelMap, TemplateDocument::class.java)

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
