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
import app.epistola.suite.documents.preview.PreviewTargetResolver
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
 * Checks template data against the contract of the published version it would render with, without
 * rendering: which fields are missing, which are wrong, and a JSON Schema for what is missing.
 *
 * Takes the same arguments as [PreviewDocument] and resolves the same version, so its answer is
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
    override val permission get() = Permission.DOCUMENT_GENERATE

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
    private val targetResolver: PreviewTargetResolver,
    private val analyzer: TemplateDataAnalyzer,
    private val pathExtractor: TemplatePathExtractor,
) : QueryHandler<AnalyzeTemplateData, TemplateDataAnalysis> {

    override fun handle(query: AnalyzeTemplateData): TemplateDataAnalysis {
        val target = targetResolver.resolve(
            tenantKey = query.tenantKey,
            catalogKey = query.catalogKey,
            templateKey = query.templateId,
            data = query.data,
            variantKey = query.variantId,
            variantSelectionCriteria = query.variantSelectionCriteria,
            versionKey = query.versionId,
            environmentKey = query.environmentId,
        )
        val contract = target.contract ?: return TemplateDataAnalysis.NO_CONTRACT
        return analyzer.analyze(contract, target.data, pathExtractor.extractReferencedPaths(target.version.templateModel))
    }
}
