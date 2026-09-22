// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.queries

import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.documents.preview.PreviewDataAnalyzer
import app.epistola.suite.documents.preview.PreviewDataInvalidException
import app.epistola.suite.documents.preview.PreviewTargetResolver
import app.epistola.suite.generation.DocumentPreviewRenderer
import app.epistola.suite.i18n.TenantLocaleResolver
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.TemplateNotFoundException
import app.epistola.suite.templates.analysis.TemplatePathExtractor
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.services.VariantSelectionCriteria
import app.epistola.suite.tenants.TenantNotFoundException
import app.epistola.suite.tenants.queries.GetTenant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode

/**
 * Preview a published version via the REST API.
 *
 * Data that breaks the contract fails with [PreviewDataInvalidException], which carries the
 * [AnalyzePreviewData] result so the caller learns which fields to supply or correct.
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
    private val analyzer: PreviewDataAnalyzer,
    private val pathExtractor: TemplatePathExtractor,
    private val renderer: DocumentPreviewRenderer,
    private val localeResolver: TenantLocaleResolver,
) : QueryHandler<PreviewDocument, ByteArray> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(query: PreviewDocument): ByteArray {
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
            if (!analysis.valid) throw PreviewDataInvalidException(analysis, target.data)
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
