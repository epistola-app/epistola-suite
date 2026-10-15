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
import app.epistola.suite.documents.preview.PreviewTargetResolver
import app.epistola.suite.documents.versionGenerationRemoved
import app.epistola.suite.generation.DocumentPreviewRenderer
import app.epistola.suite.generation.GenerationService
import app.epistola.suite.generation.release.ReleaseRenderSource
import app.epistola.suite.generation.release.ReleaseTargetResolver
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
    private val targetResolver: PreviewTargetResolver,
    private val analyzer: TemplateDataAnalyzer,
    private val pathExtractor: TemplatePathExtractor,
    private val renderer: DocumentPreviewRenderer,
    private val localeResolver: TenantLocaleResolver,
    private val releaseTargetResolver: ReleaseTargetResolver,
    private val releaseRenderSource: ReleaseRenderSource,
    private val generationService: GenerationService,
    private val schemaValidator: JsonSchemaValidator,
    private val variantResolver: VariantResolver,
    private val objectMapper: ObjectMapper,
) : QueryHandler<PreviewDocument, ByteArray> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(query: PreviewDocument): ByteArray {
        if (query.versionId != null) throw versionGenerationRemoved()
        return when {
            query.environmentId != null -> previewEnvironment(query)
            query.workingCopy -> previewWorkingCopy(query)
            else -> previewLatestRelease(query)
        }
    }

    /** The catalog's latest release, exactly as generation would render it, with the preview watermark. */
    private fun previewLatestRelease(query: PreviewDocument): ByteArray {
        val tenant = mediator.query(GetTenant(id = query.tenantId))
            ?: throw TenantNotFoundException(query.tenantId)
        val target = releaseTargetResolver.resolveLatest(
            query.tenantId,
            query.catalogKey,
            query.templateId,
            query.variantId,
            query.variantSelectionCriteria,
        )
        val inputs = releaseRenderSource.resolve(tenant, target.release, query.templateId.value, target.variantKey.value)

        // No data sent: the release's first example, with schema defaults filled in.
        val requested = if (query.data.isEmpty) inputs.firstDataExample ?: query.data else query.data
        val contract = inputs.dataModel
        val data = if (contract != null) schemaValidator.applyDefaults(contract, requested) else requested
        contract?.let {
            val analysis = analyzer.analyze(it, data, pathExtractor.extractReferencedPaths(inputs.templateModel))
            if (!analysis.valid) throw TemplateDataInvalidException(analysis, data)
        }

        @Suppress("UNCHECKED_CAST")
        val dataMap = objectMapper.convertValue(data, Map::class.java) as Map<String, Any?>
        val out = ByteArrayOutputStream()
        generationService.renderPdfFromRelease(
            inputs = inputs,
            data = dataMap,
            outputStream = out,
            metadata = PdfMetadata(title = inputs.templateName, author = tenant.name),
            culture = localeResolver.resolveCulture(tenant, inputs.variantAttributes),
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

    /** The version an environment activated. Replaced by the environment's deployed release next. */
    private fun previewEnvironment(query: PreviewDocument): ByteArray {
        // 1. Resolve variant, version, contract and data (the first example when none was sent,
        //    with schema defaults filled in so a preview matches what generation would render)
        val target = targetResolver.resolve(
            tenantKey = query.tenantId,
            catalogKey = query.catalogKey,
            templateKey = query.templateId,
            data = query.data,
            variantKey = query.variantId,
            variantSelectionCriteria = query.variantSelectionCriteria,
            versionKey = query.versionId,
            environmentKey = query.environmentId,
        )
        val version = target.version

        logger.debug(
            "Preview for tenant={} template={} variant={} version={} env={}",
            query.tenantId,
            query.templateId,
            target.variantId.key,
            version.id,
            query.environmentId,
        )

        // 2. Validate data against the contract; the exception says which fields to fix
        target.contract?.let { contract ->
            val analysis = analyzer.analyze(contract, target.data, pathExtractor.extractReferencedPaths(version.templateModel))
            if (!analysis.valid) throw TemplateDataInvalidException(analysis, target.data)
        }

        // 3. Fetch template and tenant for theme resolution
        val template = mediator.query(GetDocumentTemplate(target.variantId.templateId))
            ?: throw TemplateNotFoundException(query.tenantId, query.templateId)
        val tenant = mediator.query(GetTenant(id = query.tenantId))
            ?: throw TenantNotFoundException(query.tenantId)

        // 4. Resolve formatting culture via variant attribute → tenant default → app default
        val culture = localeResolver.resolveCulture(tenant, target.variantId)

        // 5. Render
        return renderer.render(
            tenantId = query.tenantId,
            templateModel = version.templateModel,
            version = version,
            template = template,
            tenant = tenant,
            data = target.data,
            culture = culture,
        )
    }
}
