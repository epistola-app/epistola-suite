// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.release

import app.epistola.catalog.protocol.TemplateResource
import app.epistola.suite.catalog.revisions.ReleaseContentAssembler
import app.epistola.suite.catalog.revisions.ReleaseDependencyStore
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.documents.CatalogNotReleasedException
import app.epistola.suite.documents.TemplateNotInReleaseException
import app.epistola.suite.templates.services.VariantCandidate
import app.epistola.suite.templates.services.VariantResolver
import app.epistola.suite.templates.services.VariantSelectionCriteria
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/** What a generation request renders: a variant of a template, in one release. */
data class ReleaseTarget(val release: ReleaseRef, val variantKey: VariantKey)

/**
 * Decides which release and variant a generation or preview request renders.
 *
 * The release is the catalog's latest release that kept its content. The variant is chosen from the
 * variants **that release** holds, by the same rule as always — explicit, then attribute selection,
 * then the default — so a variant added to the working copy since the release is not offered, and
 * one removed since still is.
 */
@Component
class ReleaseTargetResolver(
    private val jdbi: Jdbi,
    private val assembler: ReleaseContentAssembler,
    private val dependencyStore: ReleaseDependencyStore,
    private val variantResolver: VariantResolver,
) {

    /**
     * @throws CatalogNotReleasedException when the catalog has no release that kept its content
     * @throws TemplateNotInReleaseException when the release lacks the template, or the explicit variant
     */
    fun resolveLatest(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        templateKey: TemplateKey,
        variantKey: VariantKey?,
        criteria: VariantSelectionCriteria?,
    ): ReleaseTarget {
        val version = jdbi.withHandle<String?, Exception> { handle ->
            dependencyStore.latestRetainedRelease(handle, tenantKey, catalogKey)
        } ?: throw CatalogNotReleasedException(tenantKey, catalogKey)
        return resolveIn(tenantKey, ReleaseRef(catalogKey, version), templateKey, variantKey, criteria)
    }

    /** As [resolveLatest], for a release already chosen. */
    fun resolveIn(
        tenantKey: TenantKey,
        release: ReleaseRef,
        templateKey: TemplateKey,
        variantKey: VariantKey?,
        criteria: VariantSelectionCriteria?,
    ): ReleaseTarget {
        val template = assembler.readResource(tenantKey, release.catalogKey, release.version, "template", templateKey.value) as? TemplateResource
            ?: throw TemplateNotInReleaseException(tenantKey, release.catalogKey, release.version, templateKey, variantId = null)
        val candidates = template.variants.map { VariantCandidate(VariantKey.of(it.slug), it.attributes.orEmpty(), it.isDefault) }

        val chosen = when {
            variantKey != null -> candidates.firstOrNull { it.id == variantKey }?.id
                ?: throw TemplateNotInReleaseException(tenantKey, release.catalogKey, release.version, templateKey, variantKey)

            criteria != null -> variantResolver.select(templateKey, candidates, criteria)

            else -> (candidates.firstOrNull { it.isDefault } ?: candidates.firstOrNull())?.id
                ?: throw TemplateNotInReleaseException(tenantKey, release.catalogKey, release.version, templateKey, variantId = null)
        }
        return ReleaseTarget(release, chosen)
    }
}
