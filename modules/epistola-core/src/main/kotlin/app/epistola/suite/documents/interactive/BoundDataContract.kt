// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.interactive

import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.generation.release.ReleaseRef
import app.epistola.suite.generation.release.ReleaseRenderSource
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.SystemUser
import app.epistola.suite.templates.analysis.TemplatePathExtractor
import app.epistola.suite.templates.contracts.queries.GetDataContract
import app.epistola.suite.templates.queries.variants.GetVariantContent
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.templates.validation.TemplateDataAnalysis
import app.epistola.suite.templates.validation.TemplateDataAnalyzer
import app.epistola.suite.tenants.TenantNotFoundException
import app.epistola.suite.tenants.queries.GetTenant
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode

/**
 * The data contract of what a generation request is bound to, and the paths its template reads.
 * [dataModel] is null when the template declares no contract.
 */
data class BoundContract(val dataModel: ObjectNode?, val referencedPaths: Set<String>)

/**
 * The data contract a generation request renders against: the one in the release it was bound to
 * at acceptance, or in the draft its environment follows. Never resolved again, never another
 * version (contract ADR 0011: the form and `trigger: incomplete` are defined against the binding).
 *
 * Unlike [app.epistola.suite.documents.queries.AnalyzeTemplateData], empty data is analysed as it
 * is: a job never falls back to a data example, since empty data is an empty form, not someone
 * else's example. Reading a draft's contract needs template view, which a caller allowed only to
 * generate may not have; it is read as the tenant's system user, revealing nothing beyond the
 * missing fields the contract ADR says such a caller is told.
 */
@Component
class BoundDataContract(
    private val releaseRenderSource: ReleaseRenderSource,
    private val analyzer: TemplateDataAnalyzer,
    private val pathExtractor: TemplatePathExtractor,
    private val schemaValidator: JsonSchemaValidator,
    private val mediator: Mediator,
) {
    /** The contract of a target about to be bound: a release ([releaseVersion]) or a draft ([draftId]). */
    fun of(
        catalog: CatalogId,
        templateKey: TemplateKey,
        variantKey: VariantKey,
        releaseVersion: String?,
        draftId: String?,
        environment: EnvironmentKey?,
    ): BoundContract = SecurityContext.runWithPrincipal(SystemUser.principalForTenant(catalog.tenantKey)) {
        when {
            releaseVersion != null -> {
                val tenant = mediator.query(GetTenant(id = catalog.tenantKey)) ?: throw TenantNotFoundException(catalog.tenantKey)
                val inputs = releaseRenderSource.resolve(tenant, ReleaseRef(catalog, releaseVersion), templateKey.value, variantKey.value, environment)
                BoundContract(inputs.dataModel, pathExtractor.extractReferencedPaths(inputs.templateModel))
            }

            draftId != null -> {
                val draft = CatalogReleaseRef(catalog, DraftRef.Id(draftId))
                val templateId = TemplateId(templateKey, catalog)
                val dataModel = mediator.query(GetDataContract(templateId, draft))?.dataModel
                val model = mediator.query(GetVariantContent(VariantId(variantKey, templateId), draft))
                BoundContract(dataModel, model?.let(pathExtractor::extractReferencedPaths).orEmpty())
            }

            else -> error("A request bound to neither a release nor a draft predates 2.0 and has no form")
        }
    }

    /** The contract of an accepted [request]. */
    fun of(request: DocumentGenerationRequest): BoundContract = of(
        request.catalogId,
        request.templateKey,
        request.variantKey,
        request.releaseVersion,
        request.draftId,
        request.environmentKey,
    )

    /**
     * What is missing or invalid in [data] under [contract], with the contract's defaults applied as
     * rendering applies them. A template without a contract accepts any data.
     */
    fun analyze(contract: BoundContract, data: ObjectNode): TemplateDataAnalysis {
        val dataModel = contract.dataModel ?: return TemplateDataAnalysis.NO_CONTRACT
        return analyzer.analyze(dataModel, schemaValidator.applyDefaults(dataModel, data), contract.referencedPaths)
    }
}
