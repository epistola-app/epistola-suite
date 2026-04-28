package app.epistola.suite.generation

import app.epistola.generation.pdf.AssetResolution
import app.epistola.generation.pdf.AssetResolver
import app.epistola.generation.pdf.PdfMetadata
import app.epistola.generation.pdf.RenderingDefaults
import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.model.TemplateVersion
import app.epistola.suite.tenants.Tenant
import app.epistola.template.model.TemplateDocument
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayOutputStream

/**
 * Renders a preview PDF from resolved inputs.
 *
 * Both [PreviewDocument][app.epistola.suite.documents.queries.PreviewDocument] (API)
 * and [PreviewVariant][app.epistola.suite.documents.queries.PreviewVariant] (editor)
 * delegate here after resolving their own template model source.
 */
@Service
class DocumentPreviewRenderer(
    private val mediator: Mediator,
    private val generationService: GenerationService,
    private val objectMapper: ObjectMapper,
) {

    /**
     * Render a preview PDF and return the raw bytes.
     *
     * @param tenantId Tenant key (for asset resolution)
     * @param templateModel The resolved template document to render
     * @param version The resolved version (null for drafts/live models — uses live theme cascade)
     * @param template The document template metadata (for theme, name)
     * @param tenant The tenant (for fallback theme, author name)
     * @param data The data context as JSON
     */
    fun render(
        tenantId: TenantKey,
        templateModel: TemplateDocument,
        version: TemplateVersion?,
        template: DocumentTemplate,
        tenant: Tenant,
        data: ObjectNode,
    ): ByteArray {
        val outputStream = ByteArrayOutputStream()

        @Suppress("UNCHECKED_CAST")
        val dataMap = objectMapper.convertValue(data, Map::class.java) as Map<String, Any?>
        val metadata = PdfMetadata(
            title = template.name,
            author = tenant.name,
        )
        val assetResolver = AssetResolver { assetId ->
            mediator.query(GetAssetContent(tenantId, AssetKey.of(assetId)))
                ?.let { AssetResolution(it.content, it.mediaType.mimeType) }
        }

        // Use snapshot rendering for published versions that have it, live cascade otherwise
        val resolvedTheme = version?.resolvedTheme
        val renderingDefaultsVersion = version?.renderingDefaultsVersion
        if (resolvedTheme != null && renderingDefaultsVersion != null) {
            val renderingDefaults = RenderingDefaults.forVersion(renderingDefaultsVersion)
            val metadataWithEngine = metadata.copy(engineVersion = renderingDefaults.engineVersionString())
            generationService.renderPdfWithSnapshot(
                templateModel,
                dataMap,
                outputStream,
                resolvedTheme,
                renderingDefaults,
                metadataWithEngine,
                pdfaCompliant = false,
                assetResolver = assetResolver,
            )
        } else {
            val metadataWithEngine = metadata.copy(
                engineVersion = RenderingDefaults.CURRENT.engineVersionString(),
            )
            generationService.renderPdf(
                tenantId,
                templateModel,
                dataMap,
                outputStream,
                template.themeKey,
                tenant.defaultThemeKey,
                metadataWithEngine,
                pdfaCompliant = false,
                assetResolver = assetResolver,
                templateCatalogKey = template.themeCatalogKey,
                tenantDefaultThemeCatalogKey = tenant.defaultThemeCatalogKey,
            )
        }

        return outputStream.toByteArray()
    }
}
