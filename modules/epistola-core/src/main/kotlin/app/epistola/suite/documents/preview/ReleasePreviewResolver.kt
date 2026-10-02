// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.preview

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.generation.release.ReleaseRenderInputs
import app.epistola.suite.generation.release.ReleaseRenderSource
import app.epistola.suite.generation.release.ReleaseTargetResolver
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.templates.services.VariantSelectionCriteria
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.TenantNotFoundException
import app.epistola.suite.tenants.queries.GetTenant
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode

/** What a preview or a data analysis works from: the release's render inputs and the effective data. */
data class ReleasePreview(
    val tenant: Tenant,
    val inputs: ReleaseRenderInputs,
    /** The request's data, or the release's first example when none was sent, with schema defaults applied. */
    val data: ObjectNode,
)

/**
 * Resolves what a preview, or the analysis of preview data, renders: the release it names, the one the
 * environment serves, or the catalog's latest, exactly as generation would choose it.
 *
 * Shared by [app.epistola.suite.documents.queries.PreviewDocument] and
 * [app.epistola.suite.documents.queries.AnalyzeTemplateData], so the analysis is always about the
 * release the preview would render.
 */
@Component
class ReleasePreviewResolver(
    private val mediator: Mediator,
    private val targetResolver: ReleaseTargetResolver,
    private val renderSource: ReleaseRenderSource,
    private val schemaValidator: JsonSchemaValidator,
) {
    fun resolve(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        templateKey: TemplateKey,
        data: ObjectNode,
        variantKey: VariantKey?,
        criteria: VariantSelectionCriteria?,
        environmentKey: EnvironmentKey?,
        releaseVersion: String? = null,
    ): ReleasePreview {
        val tenant = mediator.query(GetTenant(id = tenantKey)) ?: throw TenantNotFoundException(tenantKey)
        val target = targetResolver.resolve(tenantKey, catalogKey, templateKey, variantKey, criteria, environmentKey, releaseVersion)
        val inputs = renderSource.resolve(tenant, target.release, templateKey.value, target.variantKey.value)
        val requested = if (data.isEmpty) inputs.firstDataExample ?: data else data
        val effective = inputs.dataModel?.let { schemaValidator.applyDefaults(it, requested) } ?: requested
        return ReleasePreview(tenant, inputs, effective)
    }
}
