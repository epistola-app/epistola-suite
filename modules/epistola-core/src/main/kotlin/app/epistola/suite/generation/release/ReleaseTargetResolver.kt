// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.release

import app.epistola.catalog.protocol.TemplateResource
import app.epistola.suite.catalog.CatalogVersionSelector
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.queries.LATEST_RELEASE_ORDER
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.DraftRef
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
import app.epistola.suite.environments.EnvironmentOverride
import app.epistola.suite.environments.EnvironmentOverrideStore
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

/** A draft an environment uses, live, through its override ([app.epistola.suite.environments.EnvironmentOverride]). */
data class DraftSource(val catalogKey: CatalogKey, val draftId: String, val label: String)

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
 * An environment's override ([app.epistola.suite.environments.EnvironmentOverride]) replaces the
 * choice for every request through it that names no exact release, a range included: it names one
 * of the releases the environment serves, or a draft, rendered live ([select], [resolveForGeneration]).
 */
@Component
class ReleaseTargetResolver(
    private val jdbi: Jdbi,
    private val renderSource: ReleaseRenderSource,
    private val variantResolver: VariantResolver,
    private val mediator: Mediator,
) {

    /**
     * What a request for [catalogKey] renders: [environmentKey]'s override when it has one and
     * [versionSelector] names no exact release, else the release [selectRelease] picks.
     */
    fun select(tenantKey: TenantKey, catalogKey: CatalogKey, environmentKey: EnvironmentKey?, versionSelector: String?): CatalogSelection {
        val selector = versionSelector?.trim()?.ifEmpty { null }
        if (environmentKey != null && !namesExactRelease(selector)) {
            val override = jdbi.withHandle<EnvironmentOverride?, Exception> { EnvironmentOverrideStore.list(it, tenantKey, environmentKey, catalogKey).singleOrNull() }
            when {
                override?.version != null -> return CatalogSelection.Release(override.version)
                override?.draftId != null -> return CatalogSelection.Draft(DraftSource(catalogKey, override.draftId, override.label))
            }
        }
        return CatalogSelection.Release(highestCandidate(tenantKey, catalogKey, environmentKey, selector))
    }

    /** [resolve] for generation, which also renders the draft an environment follows. */
    fun resolveForGeneration(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        templateKey: TemplateKey,
        variantKey: VariantKey?,
        criteria: VariantSelectionCriteria?,
        environmentKey: EnvironmentKey?,
        versionSelector: String?,
    ): GenerationTarget = resolveIn(tenantKey, catalogKey, select(tenantKey, catalogKey, environmentKey, versionSelector), templateKey, variantKey, criteria)

    /** The variant a request renders in [selection], for a selection already made. */
    fun resolveIn(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        selection: CatalogSelection,
        templateKey: TemplateKey,
        variantKey: VariantKey?,
        criteria: VariantSelectionCriteria?,
    ): GenerationTarget = when (selection) {
        is CatalogSelection.Release -> ReleaseRef(catalogKey, selection.version).let { release ->
            GenerationTarget(
                resolveIn(tenantKey, release, templateKey, variantKey, criteria).variantKey,
                releaseVersion = selection.version,
                draft = null,
                templateResourceId = renderSource.templateResourceId(tenantKey, release, templateKey.value),
            )
        }

        is CatalogSelection.Draft -> GenerationTarget(
            resolveInDraft(tenantKey, selection.source, templateKey, variantKey, criteria),
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
                    .bind("t", tenantKey)
                    .bind("c", selection.source.catalogKey)
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
        val templateId = TemplateId(templateKey, CatalogId(draft.catalogKey, TenantId(tenantKey)))
        val summaries = mediator.query(GetVariantSummaries(templateId, CatalogVersionRef(tenantKey, draft.catalogKey, DraftRef.Id(draft.draftId))))
        fun missing(variant: VariantKey?) = TemplateNotInReleaseException(tenantKey, draft.catalogKey, draft.label, templateKey, variant, draftLabel = draft.label)
        val candidates = summaries.map { VariantCandidate(it.id, it.attributes, it.isDefault) }
        return when {
            variantKey != null -> candidates.firstOrNull { it.id == variantKey }?.id ?: throw missing(variantKey)
            criteria != null -> variantResolver.select(templateKey, candidates, criteria)
            else -> (candidates.firstOrNull { it.isDefault } ?: candidates.firstOrNull())?.id ?: throw missing(null)
        }
    }

    /**
     * Whether [selector] names one release outright, which an override does not replace: a version
     * number, or a label that is no range (a release from before 2.0).
     */
    private fun namesExactRelease(selector: String?): Boolean = selector != null &&
        (SemVer.parseOrNull(selector) != null || CatalogVersionSelector.parseOrNull(selector) == null)

    /**
     * The release and variant a request renders: [selectRelease], then the variant within it.
     *
     * @throws TemplateNotInReleaseException when the release lacks the template, or the explicit variant
     */
    fun resolve(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        templateKey: TemplateKey,
        variantKey: VariantKey?,
        criteria: VariantSelectionCriteria?,
        environmentKey: EnvironmentKey?,
        versionSelector: String?,
    ): ReleaseTarget = resolveIn(
        tenantKey,
        ReleaseRef(catalogKey, selectRelease(tenantKey, catalogKey, environmentKey, versionSelector)),
        templateKey,
        variantKey,
        criteria,
    )

    /**
     * The release of [catalogKey] a request renders: its environment's override (a release), else the highest candidate satisfying
     * [versionSelector], or the highest candidate without one. The candidates are the releases
     * [environmentKey] serves, or without it every release of the catalog that kept its content.
     *
     * A selector that names a candidate exactly picks it, so an exact version keeps working for a
     * release whose number is not SemVer (a label from before 2.0); otherwise the selector is an npm
     * range evaluated as [CatalogVersionSelector] does. Highest is SemVer precedence for a range and
     * the catalog's release order ([LATEST_RELEASE_ORDER]) without a selector; they agree for every
     * `MAJOR.MINOR.PATCH` release.
     *
     * @throws EnvironmentNotFoundException when the environment does not exist
     * @throws NoReleaseDeployedException when the environment serves no release of the catalog, or none satisfying the selector
     * @throws ReleaseNotFoundException when, without an environment, no release that kept its content satisfies the selector
     * @throws CatalogNotReleasedException when, without either, the catalog has no release that kept its content
     * @throws ValidationException when the selector names no candidate and is not a version selector
     * @throws EnvironmentUsesDraftException when the environment's override names a draft
     */
    fun selectRelease(tenantKey: TenantKey, catalogKey: CatalogKey, environmentKey: EnvironmentKey?, versionSelector: String?): String = when (val selection = select(tenantKey, catalogKey, environmentKey, versionSelector)) {
        is CatalogSelection.Release -> selection.version
        is CatalogSelection.Draft -> throw EnvironmentUsesDraftException(environmentKey!!, catalogKey, selection.source.label)
    }

    private fun highestCandidate(tenantKey: TenantKey, catalogKey: CatalogKey, environmentKey: EnvironmentKey?, selector: String?): String {
        val chosen = jdbi.withHandle<String?, Exception> { handle ->
            if (environmentKey != null) {
                val environmentExists = handle.createQuery("SELECT EXISTS (SELECT 1 FROM environments WHERE tenant_key = :t AND id = :e)")
                    .bind("t", tenantKey)
                    .bind("e", environmentKey)
                    .mapTo(Boolean::class.java)
                    .one()
                if (!environmentExists) throw EnvironmentNotFoundException(tenantKey, environmentKey)
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
                .bind("t", tenantKey)
                .bind("c", catalogKey)
                .apply { if (environmentKey != null) bind("e", environmentKey) }
                .apply { if (":v" in condition) bind("v", selector) }
                .mapTo(String::class.java)
                .list()

            when {
                selector == null -> candidates("", "LIMIT 1").firstOrNull()

                else -> candidates("AND r.version = :v").firstOrNull() ?: run {
                    val range = CatalogVersionSelector.parseOrNull(selector)
                        ?: throw ValidationException("version", "'$selector' is not a version selector: give a release version such as 1.4.0, or a range such as ^1.4.0")
                    // A range is evaluated here, not in SQL; the candidates are one column per release.
                    range.highestMatch(candidates(""))
                }
            }
        }
        return chosen ?: when {
            environmentKey != null -> throw NoReleaseDeployedException(tenantKey, environmentKey, catalogKey, selector)
            selector != null -> throw ReleaseNotFoundException(tenantKey, catalogKey, selector)
            else -> throw CatalogNotReleasedException(tenantKey, catalogKey)
        }
    }

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
    ): ReleaseTarget = resolve(tenantKey, catalogKey, templateKey, variantKey, criteria, environmentKey = null, versionSelector = null)

    /**
     * The highest release [environmentKey] serves for [catalogKey], and the variant within it.
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
    ): ReleaseTarget = resolve(tenantKey, catalogKey, templateKey, variantKey, criteria, environmentKey, versionSelector = null)

    /** As [resolveLatest], for a release already chosen. */
    fun resolveIn(
        tenantKey: TenantKey,
        release: ReleaseRef,
        templateKey: TemplateKey,
        variantKey: VariantKey?,
        criteria: VariantSelectionCriteria?,
    ): ReleaseTarget {
        val template = renderSource.template(tenantKey, release, templateKey.value)
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
