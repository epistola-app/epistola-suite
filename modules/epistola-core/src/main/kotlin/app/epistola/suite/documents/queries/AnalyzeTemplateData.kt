// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.queries

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.documents.preview.ReleasePreviewResolver
import app.epistola.suite.documents.versionGenerationRemoved
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.analysis.TemplatePathExtractor
import app.epistola.suite.templates.services.VariantSelectionCriteria
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
    val versionId: VersionKey? = null,
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
        validate("environmentId", versionId == null || environmentId == null) {
            "Cannot specify both versionId and environmentId"
        }
    }
}

@Component
class AnalyzeTemplateDataHandler(
    private val releasePreview: ReleasePreviewResolver,
    private val analyzer: TemplateDataAnalyzer,
    private val pathExtractor: TemplatePathExtractor,
) : QueryHandler<AnalyzeTemplateData, TemplateDataAnalysis> {

    override fun handle(query: AnalyzeTemplateData): TemplateDataAnalysis {
        if (query.versionId != null) throw versionGenerationRemoved()
        val preview = releasePreview.resolve(
            query.tenantKey,
            query.catalogKey,
            query.templateId,
            query.data,
            query.variantId,
            query.variantSelectionCriteria,
            query.environmentId,
        )
        val contract = preview.inputs.dataModel ?: return TemplateDataAnalysis.NO_CONTRACT
        return analyzer.analyze(contract, preview.data, pathExtractor.extractReferencedPaths(preview.inputs.templateModel))
    }
}
