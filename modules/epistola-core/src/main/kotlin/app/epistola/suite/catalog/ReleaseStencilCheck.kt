// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.suite.catalog.revisions.ReleaseContentAssembler
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.stencils.model.StencilInstances
import app.epistola.suite.stencils.model.describe
import app.epistola.suite.stencils.model.validationCode
import app.epistola.suite.validation.ValidationException
import org.springframework.stereotype.Component

/**
 * The release check for stencil instances (contract ADR 0006): every instance in the content being
 * released must fit the stencil it resolves to. A stencil of the released catalog comes from that
 * same content; a stencil of another catalog from the release of it this release is pinned to.
 *
 * Templates' variants and stencils' own content are checked; a binding the stencil does not
 * declare is harmless and passes.
 */
@Component
class ReleaseStencilCheck(
    private val assembler: ReleaseContentAssembler,
) {
    /** @throws ValidationException naming every instance that does not fit, when one does not. */
    fun requireInstancesFit(tenantKey: TenantKey, catalogKey: CatalogKey, content: CatalogContent) {
        val own = content.resourceDetails.values.mapNotNull { it.resource as? StencilResource }.associateBy { it.key }
        // A dependency's key is its key in this tenant too: a declaration names a catalog the tenant has.
        val pins = content.catalogDependencies.orEmpty().associate { it.catalogKey to it.releaseVersion }
        val resolver = StencilInstances.Resolver { stencilKey, stencilCatalog ->
            val stencil = if (stencilCatalog == catalogKey.value) {
                own[stencilKey]
            } else {
                pins[stencilCatalog]?.let { version -> assembler.readResource(tenantKey, CatalogKey.of(stencilCatalog), version, "stencil", stencilKey) as? StencilResource }
            }
            stencil?.let { StencilInstances.Content(it.content, it.parameterSchema, it.readsTemplateData) }
        }

        val documents = content.resourceDetails.values.flatMap { detail ->
            when (val resource = detail.resource) {
                is TemplateResource -> listOf("template '${resource.key}'" to resource.templateModel) +
                    resource.variants.mapNotNull { variant -> variant.templateModel?.let { "template '${resource.key}' variant '${variant.key}'" to it } }

                is StencilResource -> listOf("stencil '${resource.key}'" to resource.content)

                else -> emptyList()
            }
        }
        val misfits = documents.flatMap { (where, document) ->
            StencilInstances.expand(document, catalogKey.value, resolver).findings.filter { it.blocking }.map { where to it }
        }
        if (misfits.isEmpty()) return
        throw ValidationException(
            field = "resources",
            message = "Cannot release ${catalogKey.value}: " + misfits.joinToString("; ") { (where, finding) -> "$where ${finding.describe()}" },
            code = misfits.map { it.second }.validationCode(),
        )
    }
}
