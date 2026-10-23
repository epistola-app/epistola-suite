// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.merge

import app.epistola.suite.catalog.releases.CatalogReleaseNotFoundException
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.springframework.stereotype.Component

/**
 * What merging [release] into a draft would do, resource by resource and for each catalog setting
 * ([CatalogSetting]) (ADR 0032, alpha): the draft the query targets, or the catalog's next version.
 * Compared against the merge base, the closest release both descend from ([app.epistola.suite.catalog.releases.ReleaseLineage.closestIn]),
 * as [DraftMergePlanner] works it out; nothing is changed. Its [DraftMergePreview.token] names the
 * state it shows, for [MergeReleaseIntoDraft.expectedToken].
 *
 * @throws CatalogReleaseNotFoundException when the catalog has no such draft, or no such release.
 */
data class PreviewDraftMerge(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val release: String,
    /** The draft to merge into; null for the catalog's next version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Query<DraftMergePreview>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

data class DraftMergePreview(
    val catalogKey: CatalogKey,
    /** What people call the draft ("Next release", or its name). */
    val draftLabel: String,
    /** The merge base: the closest release the draft and [release] both descend from; null when none. */
    val base: String?,
    val release: String,
    /** Whether the draft already contains [release]: its base, one merged before, or one either descends from. */
    val alreadyMerged: Boolean,
    /** Ordered by type, then key; catalog settings are entries of type [CatalogSetting.TYPE]. */
    val resources: List<MergeResource>,
    /** Names the state this preview shows; a merge given it refuses once the draft or the release changed. */
    val token: String = "",
    /**
     * The merge base the lines descend from that this installation does not have: nothing is
     * classified ([resources] is empty) and merging is refused until it is installed.
     */
    val missingBase: MergeBaseRef? = null,
) {
    /**
     * Whether no release both the draft and [release] descend from is known (no recorded lineage
     * meets): every entry the two have differently is then a choice, never a guess.
     */
    val noCommonRelease: Boolean get() = base == null && missingBase == null && !alreadyMerged

    val conflicts: List<MergeResource> get() = resources.filter { it.outcome == MergeOutcome.CONFLICT }

    /** What the merge takes from the release without asking. */
    val taken: List<MergeResource> get() = resources.filter { it.outcome == MergeOutcome.THEIRS }

    /** Whether a merge changes the draft at all. */
    val changesDraft: Boolean get() = taken.isNotEmpty() || conflicts.isNotEmpty()
}

@Component
class PreviewDraftMergeHandler(
    private val planner: DraftMergePlanner,
) : QueryHandler<PreviewDraftMerge, DraftMergePreview> {

    override fun handle(query: PreviewDraftMerge): DraftMergePreview {
        // The draft the query targets: the mediator scopes it, so building the content reads it too.
        val plan = planner.plan(query.tenantKey, query.catalogKey, query.release, query.catalogVersion?.draft?.toString() ?: "next")
        return plan.toPreview(query.catalogKey)
    }
}

/** [this] plan as a preview shows it. */
internal fun MergePlan.toPreview(catalogKey: CatalogKey) = DraftMergePreview(
    catalogKey = catalogKey,
    draftLabel = inputs.draft.label,
    base = inputs.mergeBase,
    release = inputs.release,
    alreadyMerged = inputs.release in inputs.contained,
    resources = resources,
    token = token,
    missingBase = inputs.missingBase,
)
