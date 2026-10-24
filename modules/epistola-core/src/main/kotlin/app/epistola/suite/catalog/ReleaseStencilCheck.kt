// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.suite.catalog.revisions.ReleaseContentAssembler
import app.epistola.suite.common.ids.CatalogId
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
    private val dependencyStore: app.epistola.suite.catalog.dependencies.CatalogDependencyStore,
    private val jdbi: org.jdbi.v3.core.Jdbi,
) {
    /** @throws ValidationException naming every instance that does not fit, when one does not. */
    fun requireInstancesFit(catalog: CatalogId, content: CatalogContent) {
        val own = content.resourceDetails.values.mapNotNull { it.resource as? StencilResource }.associateBy { it.key }
        // Each reference by alias from the catalog of the content it is in (ADR 0034, section 9): the
        // release's own declarations, and for a stencil of another catalog, that release's pins.
        val declared = content.catalogDependencies.orEmpty().map(app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration::of)
        val releases = mutableMapOf<String, Pair<CatalogId, String>>()
        val resolver = StencilInstances.Resolver { stencilKey, alias, from ->
            val (target, version) = if (from == catalog.address) {
                if (CatalogReferences.own(catalog, alias)) return@Resolver own[stencilKey]?.let { StencilInstances.Content(it.content, it.parameterSchema, it.readsTemplateData, catalog.address) }
                val pin = declared.firstOrNull { it.alias == alias } ?: return@Resolver null
                pin.catalogIn(catalog.tenantKey) to (pin.releaseVersion ?: return@Resolver null)
            } else {
                val (fromCatalog, fromVersion) = releases[from] ?: return@Resolver null
                if (CatalogReferences.own(fromCatalog, alias)) {
                    fromCatalog to fromVersion
                } else {
                    val pin = jdbi.withHandle<app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration?, Exception> { dependencyStore.releasePins(it, fromCatalog, fromVersion).firstOrNull { pin -> pin.alias == alias } }
                        ?: return@Resolver null
                    pin.catalogIn(catalog.tenantKey) to (pin.releaseVersion ?: return@Resolver null)
                }
            }
            releases.putIfAbsent(target.address, target to version)
            (assembler.readResource(target, version, "stencil", stencilKey) as? StencilResource)
                ?.let { StencilInstances.Content(it.content, it.parameterSchema, it.readsTemplateData, target.address) }
        }

        val documents = content.resourceDetails.values.flatMap { detail ->
            when (val resource = detail.resource) {
                is TemplateResource -> listOf(Triple("template '${resource.key}'", resource.templateModel, resource.dataModel)) +
                    resource.variants.mapNotNull { variant -> variant.templateModel?.let { Triple("template '${resource.key}' variant '${variant.key}'", it, resource.dataModel) } }

                is StencilResource -> listOf(Triple("stencil '${resource.key}'", resource.content, null))

                else -> emptyList()
            }
        }
        val misfits = documents.flatMap { (where, document, dataModel) ->
            StencilInstances.expand(document, catalog.address, resolver, dataModel).findings.filter { it.blocking }.map { where to it }
        }
        if (misfits.isEmpty()) return
        throw ValidationException(
            field = "resources",
            message = "Cannot release ${catalog.key.value}: " + misfits.joinToString("; ") { (where, finding) -> "$where ${finding.describe()}" },
            code = misfits.map { it.second }.validationCode(),
        )
    }
}
