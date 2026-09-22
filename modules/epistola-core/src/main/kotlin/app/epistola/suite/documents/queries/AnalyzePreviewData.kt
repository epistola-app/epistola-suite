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
import app.epistola.suite.documents.preview.PreviewDataAnalysis
import app.epistola.suite.documents.preview.PreviewDataAnalyzer
import app.epistola.suite.documents.preview.PreviewTargetResolver
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.analysis.TemplatePathExtractor
import app.epistola.suite.templates.services.VariantSelectionCriteria
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode

/**
 * Checks preview data against the contract of the version [PreviewDocument] would render, without
 * rendering: which fields are missing, which are wrong, and a JSON Schema for what is missing.
 *
 * Takes the same arguments as [PreviewDocument] and resolves the same version, so its answer is
 * exactly why that preview would fail. It never throws for bad data — that is its result.
 */
data class AnalyzePreviewData(
    val tenantId: TenantKey,
    val catalogKey: CatalogKey,
    val templateId: TemplateKey,
    val data: ObjectNode,
    val variantId: VariantKey? = null,
    val variantSelectionCriteria: VariantSelectionCriteria? = null,
    val versionId: VersionKey? = null,
    val environmentId: EnvironmentKey? = null,
) : Query<PreviewDataAnalysis>,
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
class AnalyzePreviewDataHandler(
    private val targetResolver: PreviewTargetResolver,
    private val analyzer: PreviewDataAnalyzer,
    private val pathExtractor: TemplatePathExtractor,
) : QueryHandler<AnalyzePreviewData, PreviewDataAnalysis> {

    override fun handle(query: AnalyzePreviewData): PreviewDataAnalysis {
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
        val contract = target.contract ?: return PreviewDataAnalysis.NO_CONTRACT
        return analyzer.analyze(contract, target.data, pathExtractor.extractReferencedPaths(target.version.templateModel))
    }
}
