// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.release

import app.epistola.catalog.protocol.TemplateResource
import app.epistola.suite.catalog.CatalogReleaseSelector
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.queries.LATEST_RELEASE_ORDER
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.documents.CatalogNotReleasedException
import app.epistola.suite.documents.EnvironmentNotFoundException
import app.epistola.suite.documents.NoReleaseDeployedException
import app.epistola.suite.documents.ReleaseNotFoundException
import app.epistola.suite.documents.TemplateNotInReleaseException
import app.epistola.suite.environments.DraftDeployment
import app.epistola.suite.environments.DraftDeploymentStore
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.templates.queries.variants.GetVariantSummaries
import app.epistola.suite.templates.services.VariantCandidate
import app.epistola.suite.templates.services.VariantResolver
import app.epistola.suite.templates.services.VariantSelectionCriteria
import app.epistola.suite.validation.ValidationException
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.util.UUID

/** What a generation request renders: a variant of a template, in one release. */
data class ReleaseTarget(val release: ReleaseRef, val variantKey: VariantKey)

/** A draft an environment serves, live ([app.epistola.suite.environments.DraftDeployment]). */
data class DraftSource(val catalog: CatalogId, val draftId: String, val label: String) {
    val catalogKey: CatalogKey get() = catalog.key
}

/** What a catalog's requests through an environment render: a release, or a draft it follows. */
sealed interface CatalogSelection {
    data class Release(val version: String) : CatalogSelection

    data class Draft(val source: DraftSource) : CatalogSelection
}

/**
 * What a generation request renders: a variant of a template, in a release ([releaseVersion]) or in
 * the draft its environment follows ([draft]); exactly one of them.
 */
data class GenerationTarget(
    val variantKey: VariantKey,
    val releaseVersion: String?,
    val draft: DraftSource?,
    /**
     * The template's identity in what is rendered, recorded on the request: the next version may
     * have dropped or moved the template since that release.
     */
    val templateResourceId: UUID? = null,
)

/** Refused: a release is asked of an environment that uses a draft of the catalog, which has none. */
class EnvironmentUsesDraftException(val environmentKey: EnvironmentKey, val catalogKey: CatalogKey, val draftLabel: String) : RuntimeException("Environment '${environmentKey.value}' uses '$draftLabel' of ${catalogKey.value}, which is not a release")

/**
 * Decides which release and variant a generation or preview request renders.
 *
 * The release is chosen among candidates: the releases of the catalog the request's environment
 * serves, or, without an environment, every release of the catalog that kept its content. The
 * request's version selector (npm range grammar, contract "Version selectors") picks the highest
 * candidate that satisfies it; without one, the highest candidate is rendered. The variant is
 * chosen from the variants **that release** holds, by the same rule as always — explicit, then
 * attribute selection, then the default — so a variant added to the working copy since the release
 * is not offered, and one removed since still is.
 *
 * An environment serving a draft of the catalog ([app.epistola.suite.environments.DraftDeployment])
 * renders it, live, for every request through it that names no version; a request naming a range or
 * a number gets a release, as a draft never matches one (contract ADR 0003, [select], [resolveForGeneration]).
 */
@Component
class ReleaseTargetResolver(
    private val jdbi: Jdbi,
    private val renderSource: ReleaseRenderSource,
    private val variantResolver: VariantResolver,
    private val mediator: Mediator,
) {

    /**
     * What a request for [catalogKey] renders: the draft [environmentKey] serves when [versionSelector]
     * names no version, else the release [selectRelease] picks.
     */
    fun select(catalog: CatalogId, environmentKey: EnvironmentKey?, versionSelector: String?): CatalogSelection {
        val selector = versionSelector?.trim()?.ifEmpty { null }
        if (environmentKey != null && selector == null) {
            val draft = jdbi.withHandle<DraftDeployment?, Exception> { DraftDeploymentStore.list(it, catalog.tenantKey, environmentKey, catalog).singleOrNull() }
            if (draft != null) return CatalogSelection.Draft(DraftSource(catalog, draft.draftId, draft.draftLabel))
        }
        return CatalogSelection.Release(highestCandidate(catalog, environmentKey, selector))
    }

    /** [resolve] for generation, which also renders the draft an environment follows. */
    fun resolveForGeneration(
        catalog: CatalogId,
        templateKey: TemplateKey,
        variantKey: VariantKey?,
        criteria: VariantSelectionCriteria?,
        environmentKey: EnvironmentKey?,
        versionSelector: String?,
    ): GenerationTarget = resolveIn(catalog, select(catalog, environmentKey, versionSelector), templateKey, variantKey, criteria)

    /** The variant a request renders in [selection], for a selection already made. */
    fun resolveIn(
        catalog: CatalogId,
        selection: CatalogSelection,
        templateKey: TemplateKey,
        variantKey: VariantKey?,
        criteria: VariantSelectionCriteria?,
    ): GenerationTarget = when (selection) {
        is CatalogSelection.Release -> ReleaseRef(catalog, selection.version).let { release ->
            GenerationTarget(
                resolveIn(catalog.tenantKey, release, templateKey, variantKey, criteria).variantKey,
                releaseVersion = selection.version,
                draft = null,
                templateResourceId = renderSource.templateResourceId(catalog.tenantKey, release, templateKey.value),
            )
        }

        is CatalogSelection.Draft -> GenerationTarget(
            resolveInDraft(catalog.tenantKey, selection.source, templateKey, variantKey, criteria),
            releaseVersion = null,
            draft = selection.source,
            templateResourceId = jdbi.withHandle<UUID?, Exception> { handle ->
                handle.createQuery(
                    """
                    SELECT t.resource_id FROM document_templates t
                    JOIN catalog_versions v ON v.tenant_key = t.tenant_key AND v.version_id = t.version_id
                    WHERE t.tenant_key = :t AND v.version_key = :v AND v.catalog_id = ${catalogAt("t", "c")} AND t.id = :template
                    """,
                )
                    .bind("t", catalog.tenantKey)
                    .bindCatalog("c", selection.source.catalog)
                    .bind("v", selection.source.draftId)
                    .bind("template", templateKey)
                    .mapTo(UUID::class.java)
                    .findOne()
                    .orElse(null)
            },
        )
    }

    /** The variant chosen among those [draft] holds now, by the same rule as for a release. */
    private fun resolveInDraft(tenantKey: TenantKey, draft: DraftSource, templateKey: TemplateKey, variantKey: VariantKey?, criteria: VariantSelectionCriteria?): VariantKey {
        val templateId = TemplateId(templateKey, draft.catalog)
        val summaries = mediator.query(GetVariantSummaries(templateId, CatalogReleaseRef(draft.catalog, DraftRef.Id(draft.draftId))))
        fun missing(variant: VariantKey?) = TemplateNotInReleaseException(tenantKey, draft.catalogKey, draft.label, templateKey, variant, draftLabel = draft.label)
        val candidates = summaries.map { VariantCandidate(it.id, it.attributes, it.isDefault) }
        return when {
            variantKey != null -> candidates.firstOrNull { it.id == variantKey }?.id ?: throw missing(variantKey)
            criteria != null -> variantResolver.select(templateKey, candidates, criteria)
            else -> (candidates.firstOrNull { it.isDefault } ?: candidates.firstOrNull())?.id ?: throw missing(null)
        }
    }

    /**
     * The release and variant a request renders: [selectRelease], then the variant within it.
     *
     * @throws TemplateNotInReleaseException when the release lacks the template, or the explicit variant
     */
    fun resolve(
        catalog: CatalogId,
        templateKey: TemplateKey,
        variantKey: VariantKey?,
        criteria: VariantSelectionCriteria?,
        environmentKey: EnvironmentKey?,
        versionSelector: String?,
    ): ReleaseTarget = resolveIn(
        catalog.tenantKey,
        ReleaseRef(catalog, selectRelease(catalog, environmentKey, versionSelector)),
        templateKey,
        variantKey,
        criteria,
    )

    /**
     * The release of [catalogKey] a request renders: the highest candidate satisfying
     * [versionSelector], or the highest candidate without one. The candidates are the releases
     * [environmentKey] serves, or without it every release of the catalog that kept its content.
     *
     * A selector that names a candidate exactly picks it, so an exact version keeps working for a
     * release whose number is not SemVer (a label from before 2.0); otherwise the selector is an npm
     * range evaluated as [CatalogReleaseSelector] does. Highest is SemVer precedence for a range and
     * the catalog's release order ([LATEST_RELEASE_ORDER]) without a selector; they agree for every
     * `MAJOR.MINOR.PATCH` release.
     *
     * @throws EnvironmentNotFoundException when the environment does not exist
     * @throws NoReleaseDeployedException when the environment serves no release of the catalog, or none satisfying the selector
     * @throws ReleaseNotFoundException when, without an environment, no release that kept its content satisfies the selector
     * @throws CatalogNotReleasedException when, without either, the catalog has no release that kept its content
     * @throws ValidationException when the selector names no candidate and is not a version selector
     * @throws EnvironmentUsesDraftException when the environment serves a draft, which a request naming no version renders
     */
    fun selectRelease(catalog: CatalogId, environmentKey: EnvironmentKey?, versionSelector: String?): String = when (val selection = select(catalog, environmentKey, versionSelector)) {
        is CatalogSelection.Release -> selection.version
        is CatalogSelection.Draft -> throw EnvironmentUsesDraftException(environmentKey!!, catalog.key, selection.source.label)
    }

    private fun highestCandidate(catalog: CatalogId, environmentKey: EnvironmentKey?, selector: String?): String {
        val chosen = jdbi.withHandle<String?, Exception> { handle ->
            if (environmentKey != null) {
                val environmentExists = handle.createQuery("SELECT EXISTS (SELECT 1 FROM environments WHERE tenant_key = :t AND id = :e)")
                    .bind("t", catalog.tenantKey)
                    .bind("e", environmentKey)
                    .mapTo(Boolean::class.java)
                    .one()
                if (!environmentExists) throw EnvironmentNotFoundException(catalog.tenantKey, environmentKey)
            }
            fun candidates(condition: String, limit: String = "") = handle.createQuery(
                if (environmentKey != null) {
                    """
                    SELECT r.version FROM environment_catalog_deployments d
                    JOIN catalog_releases r ON r.tenant_key = d.tenant_key AND r.catalog_id = d.catalog_id AND r.version = d.version
                    WHERE d.tenant_key = :t AND d.environment_key = :e AND d.catalog_id = ${catalogAt("t", "c")} $condition
                    $LATEST_RELEASE_ORDER $limit
                    """
                } else {
                    """
                    SELECT r.version FROM catalog_releases r
                    WHERE r.tenant_key = :t AND r.catalog_id = ${catalogAt("t", "c")} $condition
                    $LATEST_RELEASE_ORDER $limit
                    """
                },
            )
                .bind("t", catalog.tenantKey)
                .bindCatalog("c", catalog)
                .apply { if (environmentKey != null) bind("e", environmentKey) }
                .apply { if (":v" in condition) bind("v", selector) }
                .mapTo(String::class.java)
                .list()

            when {
                selector == null -> candidates("", "LIMIT 1").firstOrNull()

                else -> candidates("AND r.version = :v").firstOrNull() ?: run {
                    val range = CatalogReleaseSelector.parseOrNull(selector)
                        ?: throw ValidationException("version", "'$selector' is not a version selector: give a release version such as 1.4.0, or a range such as ^1.4.0")
                    // A range is evaluated here, not in SQL; the candidates are one column per release.
                    range.highestMatch(candidates(""))
                }
            }
        }
        return chosen ?: when {
            environmentKey != null -> throw NoReleaseDeployedException(catalog.tenantKey, environmentKey, catalog.key, selector)
            selector != null -> throw ReleaseNotFoundException(catalog, selector)
            else -> throw CatalogNotReleasedException(catalog)
        }
    }

    /**
     * @throws CatalogNotReleasedException when the catalog has no release that kept its content
     * @throws TemplateNotInReleaseException when the release lacks the template, or the explicit variant
     */
    fun resolveLatest(
        catalog: CatalogId,
        templateKey: TemplateKey,
        variantKey: VariantKey?,
        criteria: VariantSelectionCriteria?,
    ): ReleaseTarget = resolve(catalog, templateKey, variantKey, criteria, environmentKey = null, versionSelector = null)

    /**
     * The highest release [environmentKey] serves for [catalogKey], and the variant within it.
     *
     * @throws EnvironmentNotFoundException when the environment does not exist
     * @throws NoReleaseDeployedException when it serves no release of the catalog
     */
    fun resolveDeployed(
        environmentKey: EnvironmentKey,
        catalog: CatalogId,
        templateKey: TemplateKey,
        variantKey: VariantKey?,
        criteria: VariantSelectionCriteria?,
    ): ReleaseTarget = resolve(catalog, templateKey, variantKey, criteria, environmentKey, versionSelector = null)

    /** As [resolveLatest], for a release already chosen. */
    fun resolveIn(
        tenantKey: TenantKey,
        release: ReleaseRef,
        templateKey: TemplateKey,
        variantKey: VariantKey?,
        criteria: VariantSelectionCriteria?,
    ): ReleaseTarget {
        val template = renderSource.template(release, templateKey.value)
            ?: throw TemplateNotInReleaseException(tenantKey, release.catalogKey, release.version, templateKey, variantId = null)
        val candidates = template.variants.map { VariantCandidate(VariantKey.of(it.key), it.attributes.orEmpty(), it.isDefault) }

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
