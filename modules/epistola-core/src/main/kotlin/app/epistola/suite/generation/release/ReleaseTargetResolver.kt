// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.release

import app.epistola.catalog.protocol.TemplateResource
import app.epistola.suite.catalog.revisions.ReleaseContentAssembler
import app.epistola.suite.catalog.revisions.ReleaseDependencyStore
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.documents.CatalogNotReleasedException
import app.epistola.suite.documents.EnvironmentNotFoundException
import app.epistola.suite.documents.NoReleaseDeployedException
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
 * The release is the one the request's environment serves for the catalog, or, without an
 * environment, the catalog's latest release that kept its content. The variant is chosen from the
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

    /**
     * The release [environmentKey] serves for [catalogKey], and the variant within it.
     *
     * @throws EnvironmentNotFoundException when the environment does not exist
     * @throws NoReleaseDeployedException when it serves no release of the catalog
     */
    fun resolveDeployed(
        tenantKey: TenantKey,
        environmentKey: EnvironmentKey,
        catalogKey: CatalogKey,
        templateKey: TemplateKey,
        variantKey: VariantKey?,
        criteria: VariantSelectionCriteria?,
    ): ReleaseTarget = resolveIn(
        tenantKey,
        ReleaseRef(catalogKey, deployedRelease(tenantKey, environmentKey, catalogKey)),
        templateKey,
        variantKey,
        criteria,
    )

    /**
     * The version of [catalogKey] that [environmentKey] serves.
     *
     * @throws EnvironmentNotFoundException when the environment does not exist
     * @throws NoReleaseDeployedException when it serves no release of the catalog
     */
    fun deployedRelease(tenantKey: TenantKey, environmentKey: EnvironmentKey, catalogKey: CatalogKey): String = jdbi.withHandle<String?, Exception> { handle ->
        val environmentExists = handle.createQuery("SELECT EXISTS (SELECT 1 FROM environments WHERE tenant_key = :t AND id = :e)")
            .bind("t", tenantKey)
            .bind("e", environmentKey)
            .mapTo(Boolean::class.java)
            .one()
        if (!environmentExists) throw EnvironmentNotFoundException(tenantKey, environmentKey)
        handle.createQuery(
            "SELECT version FROM environment_catalog_deployments WHERE tenant_key = :t AND environment_key = :e AND catalog_key = :c",
        )
            .bind("t", tenantKey)
            .bind("e", environmentKey)
            .bind("c", catalogKey)
            .mapTo(String::class.java)
            .findOne()
            .orElse(null)
    } ?: throw NoReleaseDeployedException(tenantKey, environmentKey, catalogKey)

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
