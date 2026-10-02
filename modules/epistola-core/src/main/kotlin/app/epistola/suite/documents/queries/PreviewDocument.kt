// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.queries

import app.epistola.generation.pdf.PdfMetadata
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.documents.DefaultVariantNotFoundException
import app.epistola.suite.documents.preview.ReleasePreviewResolver
import app.epistola.suite.documents.versionGenerationRemoved
import app.epistola.suite.generation.DocumentPreviewRenderer
import app.epistola.suite.generation.GenerationService
import app.epistola.suite.i18n.TenantLocaleResolver
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.TemplateNotFoundException
import app.epistola.suite.templates.analysis.TemplatePathExtractor
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.variants.GetVariantSummaries
import app.epistola.suite.templates.services.VariantResolver
import app.epistola.suite.templates.services.VariantSelectionCriteria
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.templates.validation.TemplateDataAnalyzer
import app.epistola.suite.templates.validation.TemplateDataInvalidException
import app.epistola.suite.tenants.TenantNotFoundException
import app.epistola.suite.tenants.queries.GetTenant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayOutputStream

/**
 * Preview a published version via the REST API.
 *
 * Data that breaks the contract fails with [TemplateDataInvalidException], which carries the
 * [AnalyzeTemplateData] result so the caller learns which fields to supply or correct.
 *
 * @property tenantId Tenant that owns the template
 * @property templateId Template to preview
 * @property data JSON data to populate the template
 * @property variantId Explicit variant (mutually exclusive with variantSelectionCriteria)
 * @property variantSelectionCriteria Attribute criteria for auto-selecting a variant
 * @property versionId Explicit version (mutually exclusive with environmentId)
 * @property environmentId Environment to determine version from (mutually exclusive with versionId)
 */
data class PreviewDocument(
    val tenantId: TenantKey,
    val catalogKey: app.epistola.suite.common.ids.CatalogKey,
    val templateId: TemplateKey,
    val data: ObjectNode,
    val variantId: VariantKey? = null,
    val variantSelectionCriteria: VariantSelectionCriteria? = null,
    val versionId: VersionKey? = null,
    val environmentId: EnvironmentKey? = null,
    /**
     * Render the working copy (each variant's draft, live theme and resources) instead of a release.
     * Generation never does this; a preview may, so an author can see unreleased work.
     */
    val workingCopy: Boolean = false,
) : Query<ByteArray>,
    RequiresPermission {
    override val permission get() = Permission.DOCUMENT_GENERATE
    override val tenantKey get() = tenantId

    init {
        require(variantId == null || variantSelectionCriteria == null) {
            "Cannot specify both variantId and variantSelectionCriteria"
        }
        require(!(versionId != null && environmentId != null)) {
            "Cannot specify both versionId and environmentId"
        }
    }
}

@Component
class PreviewDocumentHandler(
    private val mediator: Mediator,
    private val releasePreview: ReleasePreviewResolver,
    private val analyzer: TemplateDataAnalyzer,
    private val pathExtractor: TemplatePathExtractor,
    private val localeResolver: TenantLocaleResolver,
    private val generationService: GenerationService,
    private val variantResolver: VariantResolver,
    private val objectMapper: ObjectMapper,
) : QueryHandler<PreviewDocument, ByteArray> {

    override fun handle(query: PreviewDocument): ByteArray {
        if (query.versionId != null) throw versionGenerationRemoved()
        return if (query.workingCopy) previewWorkingCopy(query) else previewRelease(query)
    }

    /**
     * The release the environment serves, or the catalog's latest without one, exactly as generation
     * would render it, with the preview watermark.
     */
    private fun previewRelease(query: PreviewDocument): ByteArray {
        val preview = releasePreview.resolve(
            query.tenantId,
            query.catalogKey,
            query.templateId,
            query.data,
            query.variantId,
            query.variantSelectionCriteria,
            query.environmentId,
        )
        val inputs = preview.inputs
        inputs.dataModel?.let {
            val analysis = analyzer.analyze(it, preview.data, pathExtractor.extractReferencedPaths(inputs.templateModel))
            if (!analysis.valid) throw TemplateDataInvalidException(analysis, preview.data)
        }

        @Suppress("UNCHECKED_CAST")
        val dataMap = objectMapper.convertValue(preview.data, Map::class.java) as Map<String, Any?>
        val out = ByteArrayOutputStream()
        generationService.renderPdfFromRelease(
            inputs = inputs,
            data = dataMap,
            outputStream = out,
            metadata = PdfMetadata(title = inputs.templateName, author = preview.tenant.name),
            culture = localeResolver.resolveCulture(preview.tenant, inputs.variantAttributes),
            watermarkText = DocumentPreviewRenderer.PREVIEW_WATERMARK,
        )
        return out.toByteArray()
    }

    /** Each variant's draft with live resources: the editor's view, reachable from the API. */
    private fun previewWorkingCopy(query: PreviewDocument): ByteArray {
        val templateId = TemplateId(query.templateId, CatalogId(query.catalogKey, TenantId(query.tenantId)))
        val variant = query.variantId
            ?: query.variantSelectionCriteria?.let { variantResolver.resolve(templateId, it) }
            ?: mediator.query(GetVariantSummaries(templateId)).firstOrNull { it.isDefault }?.id
            ?: throw DefaultVariantNotFoundException(query.tenantId, query.templateId)
        return mediator.query(
            PreviewVariant(
                tenantId = query.tenantId,
                catalogKey = query.catalogKey,
                templateId = query.templateId,
                variantId = variant,
                data = query.data,
            ),
        )
    }
}
