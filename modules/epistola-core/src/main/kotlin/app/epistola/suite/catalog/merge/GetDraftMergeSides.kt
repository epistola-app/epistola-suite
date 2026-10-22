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
 * The three sides of one entry of merging [release] into a draft (contract ADR 0005, alpha): its
 * content in the merge base, the draft and the release, in the portable catalog format
 * ([MergeEntryContent]), so that a person, an assistant or a tool can combine a conflict's sides into
 * merged content ([MergeReleaseIntoDraft.mergedContent]). [entry] (`type/key`) is one the preview
 * lists: taken, kept or a conflict. Nothing is changed.
 *
 * @throws CatalogReleaseNotFoundException when the catalog has no such draft, or no such release.
 * @throws MergeEntryNotFoundException when the merge does not list [entry].
 * @throws MergeBaseMissingException when this installation does not have the merge base.
 */
data class GetDraftMergeSides(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val release: String,
    val entry: String,
    /** The draft to merge into; null for the catalog's next version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Query<DraftMergeSides>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

/** One entry of a merge on its three sides, each in the portable catalog format, null where a side lacks it. */
data class DraftMergeSides(
    /** `type/key`. */
    val entry: String,
    /** The merge base [base] is read from; null when none is known, and [base] is null too. */
    val mergeBase: String?,
    val base: Map<String, Any?>?,
    /** Ours. */
    val draft: Map<String, Any?>?,
    /** Theirs: the release merged in. */
    val release: Map<String, Any?>?,
    /** The preview's [DraftMergePreview.token]: merged content made from these sides is merged with it. */
    val token: String,
)

/** An entry the merge does not list: no side changed it, or no side has it. */
class MergeEntryNotFoundException(val catalogKey: CatalogKey, val release: String, val entry: String) : RuntimeException("Merging v$release into '${catalogKey.value}' lists no entry $entry")

@Component
class GetDraftMergeSidesHandler(
    private val planner: DraftMergePlanner,
    private val content: MergeEntryContent,
) : QueryHandler<GetDraftMergeSides, DraftMergeSides> {

    override fun handle(query: GetDraftMergeSides): DraftMergeSides {
        val plan = planner.plan(query.tenantKey, query.catalogKey, query.release, query.catalogVersion?.draft?.toString() ?: "next")
        plan.inputs.missingBase?.let { throw MergeBaseMissingException(query.catalogKey, query.release, it.version, it.fingerprint) }
        val index = plan.resources.indexOfFirst { it.contentKey == query.entry && it.outcome in LISTED }
        if (index < 0) throw MergeEntryNotFoundException(query.catalogKey, query.release, query.entry)
        val sides = plan.entries[index]
        return DraftMergeSides(
            entry = query.entry,
            mergeBase = plan.inputs.mergeBase,
            base = content.of(sides.base, plan.inputs.base),
            draft = content.of(sides.ours, plan.inputs.ours),
            release = content.of(sides.theirs, plan.inputs.theirs),
            token = plan.token,
        )
    }

    private companion object {
        /** The outcomes a preview lists. */
        val LISTED = setOf(MergeOutcome.OURS, MergeOutcome.THEIRS, MergeOutcome.CONFLICT)
    }
}
