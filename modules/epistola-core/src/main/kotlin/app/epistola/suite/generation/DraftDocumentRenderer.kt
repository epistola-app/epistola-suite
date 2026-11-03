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
import app.epistola.suite.catalog.bindVersion
import app.epistola.suite.catalog.dependencies.DraftDependencyView
import app.epistola.suite.catalog.dependencies.DraftDependencyViews
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.ContentVersion
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.releases.GetCatalogThemeSettings
import app.epistola.suite.catalog.releases.versionOf
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.fonts.fontFamilyResolver
import app.epistola.suite.i18n.TenantLocaleResolver
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.StencilResolvers
import app.epistola.suite.templates.contracts.queries.GetDataContract
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.variants.GetVariant
import app.epistola.suite.templates.queries.variants.GetVariantContent
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.template.model.TemplateDocument
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * What rendering a variant of a draft needs, read in one short transaction on that draft: the
 * template with its stencils expanded, its theme resolved, the data with the contract's defaults
 * applied and validated, and the formatting culture. The render itself happens after, outside it.
 */
data class PreparedDraftRender(
    val model: TemplateDocument,
    val templateName: String,
    val pdfaEnabled: Boolean,
    val owning: CatalogId,
    val theme: ResolvedTheme,
    val data: ObjectNode,
    val culture: RenderCulture,
    val tenantName: String,
    /** The template's identity, for the document row. */
    val templateResourceId: UUID?,
    /** Where the draft reads other catalogs' images and fonts, read after this transaction. */
    val dependencies: DraftDependencyView,
)

/**
 * Reads what [DraftDocumentRenderer] renders, on the draft [catalogVersion] names.
 *
 * @throws IllegalArgumentException when [data] does not satisfy the draft's data contract
 */
data class PrepareDraftRender(
    val catalog: CatalogId,
    val templateId: TemplateKey,
    val variantId: VariantKey,
    val data: ObjectNode,
    override val catalogVersion: CatalogReleaseRef,
) : CatalogReleaseScoped,
    Query<PreparedDraftRender>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.TEMPLATE_VIEW
}

@Component
class PrepareDraftRenderHandler(
    private val jdbi: Jdbi,
    private val mediator: Mediator,
    private val schemaValidator: JsonSchemaValidator,
    private val localeResolver: TenantLocaleResolver,
    private val stencilResolvers: StencilResolvers,
    private val generationService: GenerationService,
    private val dependencyViews: DraftDependencyViews,
) : QueryHandler<PrepareDraftRender, PreparedDraftRender> {

    override fun handle(query: PrepareDraftRender): PreparedDraftRender {
        val templateId = TemplateId(query.templateId, query.catalog)
        val variantId = VariantId(query.variantId, templateId)
        val draft = query.catalogVersion
        val at = jdbi.withHandle<ContentVersion, Exception> { it.versionOf(query.catalog, query) }
        val model = mediator.query(GetVariantContent(variantId, catalogVersion = draft))
            ?: throw IllegalStateException("Variant ${query.variantId} not found")
        val template = mediator.query(GetDocumentTemplate(templateId, catalogVersion = draft))
            ?: throw IllegalStateException("Template ${query.templateId} not found")
        val tenant = mediator.query(GetTenant(id = query.tenantKey))
            ?: throw IllegalStateException("Tenant ${query.tenantKey} not found")

        // The draft's contract, as generation from this draft promises it.
        val dataModel = mediator.query(GetDataContract(templateId, catalogVersion = draft))?.dataModel
        val data = if (dataModel != null) schemaValidator.applyDefaults(dataModel, query.data) else query.data
        if (dataModel != null) {
            val errors = schemaValidator.validate(dataModel, data)
            if (errors.isNotEmpty()) {
                throw IllegalArgumentException("Data validation failed: ${errors.joinToString("; ") { "${it.path}: ${it.message}" }}")
            }
        }

        val dependencies = dependencyViews.of(at)
        val expanded = stencilResolvers.expandWithWorkingCopies(at, model, dependencies)
        // The catalog's own default theme (contract ADR 0007): what a template naming none renders with.
        val catalogTheme = GetCatalogThemeSettings(query.catalog, catalogVersion = draft).query().defaultTheme
        val theme = generationService.resolveTheme(
            at,
            expanded,
            template.themeKey,
            catalogTheme?.themeKey,
            templateThemeCatalog = template.themeCatalogId,
            catalogDefaultThemeCatalog = catalogTheme?.catalog,
            dependencies = dependencies,
        )
        val templateResourceId = jdbi.withHandle<UUID?, Exception> { handle ->
            handle.createQuery(
                "SELECT resource_id FROM version_templates WHERE tenant_key = :t AND version_id = :v AND id = :template",
            )
                .bind("t", query.tenantKey)
                .bindVersion("v", at.versionId)
                .bind("template", query.templateId)
                .mapTo(UUID::class.java)
                .findOne()
                .orElse(null)
        }
        return PreparedDraftRender(
            model = expanded,
            templateName = template.name,
            pdfaEnabled = template.pdfaEnabled,
            // Unqualified font references belong to the template's theme catalog, then the catalog default's.
            owning = template.themeCatalogId ?: catalogTheme?.catalog ?: query.catalog,
            theme = theme,
            data = data,
            culture = localeResolver.resolveCulture(tenant, mediator.query(GetVariant(variantId, catalogVersion = draft))?.attributes ?: emptyMap()),
            tenantName = tenant.name,
            templateResourceId = templateResourceId,
            dependencies = dependencies,
        )
    }
}

/**
 * Renders a variant of a draft for generation (an environment following the draft): the inputs are
 * read in one short transaction on the draft ([PrepareDraftRender]), the PDF is rendered outside
 * any, and each image or font it embeds is read by its own short query on the draft. So a render
 * holds no database connection while iText lays out the document, as a render of a release does.
 *
 * It carries the preview watermark: a document from unpublished work says so.
 */
@Component
class DraftDocumentRenderer(
    private val mediator: Mediator,
    private val generationService: GenerationService,
    private val objectMapper: ObjectMapper,
) {

    data class Rendered(val pdf: ByteArray, val templateResourceId: UUID?)

    fun render(catalog: CatalogId, templateKey: TemplateKey, variantKey: VariantKey, data: ObjectNode, draftId: String): Rendered {
        val draft = CatalogReleaseRef(catalog, DraftRef.Id(draftId))
        val prepared = mediator.query(PrepareDraftRender(catalog, templateKey, variantKey, data, draft))

        val assets = draftAssetResolver(catalog, draft, prepared.dependencies)

        @Suppress("UNCHECKED_CAST")
        val dataMap = objectMapper.convertValue(prepared.data, Map::class.java) as Map<String, Any?>
        val out = ByteArrayOutputStream()
        generationService.renderPdf(
            prepared.model,
            dataMap,
            out,
            prepared.theme,
            metadata = PdfMetadata(title = prepared.templateName, author = prepared.tenantName, engineVersion = RenderingDefaults.CURRENT.engineVersionString()),
            pdfaCompliant = prepared.pdfaEnabled,
            assetResolver = assets,
            fontFamilyResolver = fontFamilyResolver(prepared.owning, catalogVersion = draft, dependencies = prepared.dependencies),
            culture = prepared.culture,
            watermarkText = DocumentPreviewRenderer.PREVIEW_WATERMARK,
        )
        return Rendered(out.toByteArray(), prepared.templateResourceId)
    }
}
