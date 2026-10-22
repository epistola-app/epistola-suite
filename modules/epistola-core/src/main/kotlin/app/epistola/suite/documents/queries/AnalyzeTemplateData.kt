// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.queries

import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.documents.preview.ReleasePreviewResolver
import app.epistola.suite.generation.release.CatalogSelection
import app.epistola.suite.generation.release.ReleaseTargetResolver
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.analysis.TemplatePathExtractor
import app.epistola.suite.templates.contracts.queries.GetDataContract
import app.epistola.suite.templates.queries.variants.GetVariantContent
import app.epistola.suite.templates.services.VariantSelectionCriteria
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.templates.validation.TemplateDataAnalysis
import app.epistola.suite.templates.validation.TemplateDataAnalyzer
import app.epistola.suite.validation.validate
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode

/**
 * Checks template data against the contract of the release it would render from, without rendering:
 * which fields are missing, which are wrong, and a JSON Schema for what is missing.
 *
 * Takes the same arguments as [PreviewDocument] and resolves the same release, so its answer is
 * exactly why that preview would fail. It never throws for bad data — that is its result.
 */
data class AnalyzeTemplateData(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val templateId: TemplateKey,
    val data: ObjectNode,
    val variantId: VariantKey? = null,
    val variantSelectionCriteria: VariantSelectionCriteria? = null,
    /** A version selector, as [PreviewDocument.releaseVersion]; combines with [environmentId]. */
    val releaseVersion: String? = null,
    val environmentId: EnvironmentKey? = null,
) : Query<TemplateDataAnalysis>,
    RequiresPermission {
    // Template view, not document generate: nothing is rendered, and the answer reveals no more than
    // the contract itself, which a template viewer can already read.
    override val permission get() = Permission.TEMPLATE_VIEW

    init {
        validate("variantSelectionCriteria", variantId == null || variantSelectionCriteria == null) {
            "Cannot specify both variantId and variantSelectionCriteria"
        }
    }
}

@Component
class AnalyzeTemplateDataHandler(
    private val releasePreview: ReleasePreviewResolver,
    private val analyzer: TemplateDataAnalyzer,
    private val pathExtractor: TemplatePathExtractor,
    private val targetResolver: ReleaseTargetResolver,
    private val schemaValidator: JsonSchemaValidator,
    private val mediator: Mediator,
) : QueryHandler<AnalyzeTemplateData, TemplateDataAnalysis> {

    override fun handle(query: AnalyzeTemplateData): TemplateDataAnalysis {
        // Through an environment that follows a draft, the draft's contract is the one generation applies.
        val selection = query.environmentId?.let { targetResolver.select(query.tenantKey, query.catalogKey, it, query.releaseVersion) }
        if (selection is CatalogSelection.Draft) return analyzeDraft(query, selection)
        val preview = releasePreview.resolve(
            query.tenantKey,
            query.catalogKey,
            query.templateId,
            query.data,
            query.variantId,
            query.variantSelectionCriteria,
            query.environmentId,
            query.releaseVersion,
        )
        val contract = preview.inputs.dataModel ?: return TemplateDataAnalysis.NO_CONTRACT
        return analyzer.analyze(contract, preview.data, pathExtractor.extractReferencedPaths(preview.inputs.templateModel))
    }

    private fun analyzeDraft(query: AnalyzeTemplateData, selection: CatalogSelection.Draft): TemplateDataAnalysis {
        val draft = CatalogReleaseRef(query.tenantKey, query.catalogKey, DraftRef.Id(selection.source.draftId))
        val templateId = TemplateId(query.templateId, CatalogId(query.catalogKey, TenantId(query.tenantKey)))
        val variant = targetResolver.resolveIn(query.tenantKey, query.catalogKey, selection, query.templateId, query.variantId, query.variantSelectionCriteria).variantKey
        val contract = mediator.query(GetDataContract(templateId, draft))
        val dataModel = contract?.dataModel ?: return TemplateDataAnalysis.NO_CONTRACT
        val model = mediator.query(GetVariantContent(VariantId(variant, templateId), draft)) ?: return TemplateDataAnalysis.NO_CONTRACT
        val requested = if (query.data.isEmpty) contract.dataExamples.firstOrNull()?.data ?: query.data else query.data
        return analyzer.analyze(dataModel, schemaValidator.applyDefaults(dataModel, requested), pathExtractor.extractReferencedPaths(model))
    }
}
