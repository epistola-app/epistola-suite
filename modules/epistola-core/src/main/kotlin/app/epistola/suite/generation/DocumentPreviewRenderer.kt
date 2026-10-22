// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation

import app.epistola.generation.RenderCulture
import app.epistola.generation.pdf.AssetResolution
import app.epistola.generation.pdf.AssetResolver
import app.epistola.generation.pdf.PdfMetadata
import app.epistola.generation.pdf.RenderingDefaults
import app.epistola.generation.pdf.ResolvedTheme
import app.epistola.suite.assets.draftAssetResolver
import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.catalog.dependencies.DraftDependencyViews
import app.epistola.suite.catalog.releases.GetCatalogThemeSettings
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.fontFamilyResolver
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.StencilResolvers
import app.epistola.suite.templates.DocumentTemplate
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
    private val stencilResolvers: StencilResolvers,
    private val dependencyViews: DraftDependencyViews,
) {

    /**
     * Render a preview PDF and return the raw bytes.
     *
     * @param tenantId Tenant key (for asset resolution)
     * @param templateModel The resolved template document to render
     * @param template The document template metadata (for theme, name)
     * @param tenant The tenant (for fallback theme, author name)
     * @param data The data context as JSON
     */
    fun render(
        tenantId: TenantKey,
        templateModel: TemplateDocument,
        template: DocumentTemplate,
        tenant: Tenant,
        data: ObjectNode,
        culture: RenderCulture = RenderCulture.DEFAULT,
        resolvedThemeOverride: ResolvedTheme? = null,
    ): ByteArray {
        val outputStream = ByteArrayOutputStream()
        // The draft's declarations say where other catalogs come from, as its release would render.
        val dependencies = dependencyViews.of(tenantId, template.catalogKey)
        val expandedModel = stencilResolvers.expandWithWorkingCopies(tenantId, template.catalogKey, templateModel, dependencies)

        @Suppress("UNCHECKED_CAST")
        val dataMap = objectMapper.convertValue(data, Map::class.java) as Map<String, Any?>
        val metadata = PdfMetadata(
            title = template.name,
            author = tenant.name,
        )
        val assetResolver = draftAssetResolver(tenantId, null, dependencies)
        // The catalog's own default theme (contract ADR 0007): what a template naming none renders with.
        val catalogTheme = GetCatalogThemeSettings(tenantId, template.catalogKey).query().defaultTheme
        // Owning catalog for unqualified font refs: same cascade the theme
        // resolution + asset binding use (template theme catalog → the
        // catalog's default theme's catalog → the template's own catalog).
        val owningCatalogKey =
            template.themeCatalogKey ?: catalogTheme?.catalogKey ?: template.catalogKey
        val fontResolver = fontFamilyResolver(tenantId, owningCatalogKey, dependencies = dependencies)
        val theme = resolvedThemeOverride ?: generationService.resolveTheme(
            tenantId,
            expandedModel,
            template.themeKey,
            catalogTheme?.themeKey,
            templateCatalogKey = template.themeCatalogKey,
            catalogDefaultThemeCatalogKey = catalogTheme?.catalogKey,
            dependencies = dependencies,
        )

        val metadataWithEngine = metadata.copy(
            engineVersion = RenderingDefaults.CURRENT.engineVersionString(),
        )
        generationService.renderPdf(
            tenantId,
            expandedModel,
            dataMap,
            outputStream,
            template.themeKey,
            catalogTheme?.themeKey,
            metadataWithEngine,
            pdfaCompliant = template.pdfaEnabled,
            assetResolver = assetResolver,
            fontFamilyResolver = fontResolver,
            templateCatalogKey = template.themeCatalogKey,
            catalogDefaultThemeCatalogKey = catalogTheme?.catalogKey,
            culture = culture,
            watermarkText = PREVIEW_WATERMARK,
            resolvedThemeOverride = theme,
        )

        return outputStream.toByteArray()
    }

    companion object {
        /**
         * Watermark stamped on every preview/draft render. Rendered as a
         * header/footer brand band plus a faint centre ghost (see
         * [WatermarkEventHandler][app.epistola.generation.pdf.WatermarkEventHandler]),
         * so it marks the output as a preview and doubles as brand awareness while
         * keeping the body readable. Ensures the fast synchronous preview path
         * (editor, REST `/preview`, MCP `preview_document`) can't be mistaken for —
         * or abused as — the real generation endpoint. Final/batch generation passes
         * no watermark.
         */
        const val PREVIEW_WATERMARK = "Epistola Preview"
    }
}
