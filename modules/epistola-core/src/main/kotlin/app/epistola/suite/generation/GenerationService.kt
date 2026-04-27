package app.epistola.suite.generation

import app.epistola.generation.pdf.AssetResolver
import app.epistola.generation.pdf.DirectPdfRenderer
import app.epistola.generation.pdf.PdfMetadata
import app.epistola.generation.pdf.RenderingDefaults
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.templates.validation.ValidationError
import app.epistola.suite.themes.ResolvedThemeSnapshot
import app.epistola.suite.themes.ThemeStyleResolver
import app.epistola.template.model.TemplateDocument
import org.jdbi.v3.core.Jdbi
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
    private val jdbi: Jdbi,
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
        tenantId: TenantKey,
        templateModel: TemplateDocument,
        data: Map<String, Any?>,
        outputStream: OutputStream,
        templateDefaultThemeId: ThemeKey? = null,
        tenantDefaultThemeId: ThemeKey? = null,
        metadata: PdfMetadata = PdfMetadata(),
        pdfaCompliant: Boolean = false,
        assetResolver: AssetResolver? = null,
        renderingDefaults: RenderingDefaults = RenderingDefaults.CURRENT,
        templateCatalogKey: CatalogKey? = null,
        tenantDefaultThemeCatalogKey: CatalogKey? = null,
    ) {
        // Resolve styles from theme (variant-level > template-level > tenant-level)
        val resolvedStyles = themeStyleResolver.resolveStyles(
            tenantId,
            templateDefaultThemeId,
            tenantDefaultThemeId,
            templateModel,
            templateCatalogKey = templateCatalogKey,
            tenantDefaultThemeCatalogKey = tenantDefaultThemeCatalogKey,
        )

        pdfRenderer.render(
            document = templateModel,
            data = data,
            outputStream = outputStream,
            blockStylePresets = resolvedStyles.blockStylePresets.mapValues { (_, preset) -> preset.styles },
            resolvedDocumentStyles = resolvedStyles.documentStyles,
            metadata = metadata,
            pdfaCompliant = pdfaCompliant,
            assetResolver = assetResolver,
            renderingDefaults = renderingDefaults,
            spacingUnit = resolvedStyles.spacingUnit,
        )
    }

    /**
     * Renders a PDF using a pre-resolved theme snapshot (for published versions).
     * Bypasses live theme resolution entirely for deterministic output.
     *
     * @param templateModel The template document
     * @param data The data context for expression evaluation
     * @param outputStream The output stream to write the PDF to
     * @param themeSnapshot The frozen theme snapshot from publish time
     * @param renderingDefaults The versioned rendering defaults from publish time
     */
    fun renderPdfWithSnapshot(
        templateModel: TemplateDocument,
        data: Map<String, Any?>,
        outputStream: OutputStream,
        themeSnapshot: ResolvedThemeSnapshot,
        renderingDefaults: RenderingDefaults,
        metadata: PdfMetadata = PdfMetadata(),
        pdfaCompliant: Boolean = false,
        assetResolver: AssetResolver? = null,
    ) {
        pdfRenderer.render(
            document = templateModel,
            data = data,
            outputStream = outputStream,
            blockStylePresets = themeSnapshot.blockStylePresets,
            resolvedDocumentStyles = themeSnapshot.documentStyles,
            metadata = metadata,
            pdfaCompliant = pdfaCompliant,
            assetResolver = assetResolver,
            renderingDefaults = renderingDefaults,
            spacingUnit = themeSnapshot.spacingUnit,
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
        tenantId: TenantKey,
        catalogKey: app.epistola.suite.common.ids.CatalogKey = app.epistola.suite.common.ids.CatalogKey.DEFAULT,
        templateId: TemplateKey,
        data: Map<String, Any?>,
    ): PreviewValidationResult {
        // Load the latest contract version's data model for validation
        val contractVersion = app.epistola.suite.templates.contracts.queries.GetLatestContractVersion(
            templateId = app.epistola.suite.common.ids.TemplateId(
                templateId,
                app.epistola.suite.common.ids.CatalogId(catalogKey, app.epistola.suite.common.ids.TenantId(tenantId)),
            ),
        ).query()
        val dataModel: ObjectNode? = contractVersion?.dataModel

        if (dataModel == null) {
            return PreviewValidationResult(valid = true) // No schema means no validation
        }

        val dataNode = objectMapper.valueToTree<ObjectNode>(data)
        val errors = schemaValidator.validate(dataModel, dataNode)

        return PreviewValidationResult(
            valid = errors.isEmpty(),
            errors = errors,
        )
    }
}
