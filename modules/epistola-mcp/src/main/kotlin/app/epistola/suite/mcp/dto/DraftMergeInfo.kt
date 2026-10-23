// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.catalog.merge.DraftMergePreview
import app.epistola.suite.catalog.merge.DraftMergeSides
import app.epistola.suite.catalog.merge.MergeOutcome
import app.epistola.suite.catalog.merge.MergeResource
import app.epistola.suite.catalog.merge.MergedRelease

/**
 * What merging a release into a draft would do (ADR 0032, alpha). Lists only the resources a side
 * changed; [unchanged] counts the rest. A `CONFLICT` needs a resolution before `merge_release`.
 */
data class DraftMergeInfo(
    val draft: String,
    val base: String?,
    val release: String,
    val alreadyMerged: Boolean,
    val resources: List<MergeResourceInfo>,
    val unchanged: Int,
    /** Names the state this preview shows; `merge_release` given it refuses once something changed. */
    val token: String,
    /**
     * The release both the draft and the release come from, which this installation does not have:
     * nothing is compared until it is installed, and `merge_release` is refused.
     */
    val missingBase: MergeBaseInfo? = null,
    /** What the user needs to know first: the release to get, or that no release both come from is known. */
    val notice: String? = null,
) {
    companion object {
        fun from(preview: DraftMergePreview) = DraftMergeInfo(
            draft = preview.draftLabel,
            base = preview.base,
            release = preview.release,
            alreadyMerged = preview.alreadyMerged,
            resources = preview.resources.filter { it.outcome !in QUIET }.map(MergeResourceInfo::from),
            unchanged = preview.resources.count { it.outcome in QUIET },
            token = preview.token,
            missingBase = preview.missingBase?.let { MergeBaseInfo(it.version, it.fingerprint) },
            notice = when {
                preview.missingBase != null ->
                    "To merge v${preview.release}, this installation needs v${preview.missingBase!!.version}, the release both come from. " +
                        "Get it first (from a registry, the Exchange or a file)."

                preview.noCommonRelease ->
                    "No release both the draft and v${preview.release} come from is known, so every difference is a CONFLICT for the user to choose."

                else -> null
            },
        )

        private val QUIET = setOf(MergeOutcome.UNCHANGED, MergeOutcome.SAME)
    }
}

/** A release by its number and its catalog fingerprint. */
data class MergeBaseInfo(val version: String, val fingerprint: String)

/**
 * One resource of a merge: [resource] is `type/key`, as a resolution names it. [outcome] is `OURS`
 * (only the draft changed it, it stays), `THEIRS` (only the release did, it is taken) or `CONFLICT`;
 * [draftChange] and [releaseChange] say how each side changed it (`ADDED`, `MODIFIED`, `REMOVED`).
 * [needs] lists the other entries (`type/key`) it goes with: what its values refer to that the merge
 * also changes (a template's theme, the `catalogDependency/<key>` a reference into another catalog
 * resolves through), to be chosen together.
 */
data class MergeResourceInfo(
    val resource: String,
    val name: String?,
    val outcome: String,
    val draftChange: String?,
    val releaseChange: String?,
    val needs: List<String> = emptyList(),
) {
    companion object {
        fun from(resource: MergeResource) = MergeResourceInfo(
            resource = resource.contentKey,
            name = resource.name,
            outcome = resource.outcome.name,
            draftChange = resource.ours?.name,
            releaseChange = resource.theirs?.name,
            needs = resource.needs,
        )
    }
}

/**
 * What a merge did, each as `type/key`: [taken] from the release, [removed] as the release removed
 * them, conflicts [kept] as the draft has them, and conflicts resolved to [merged] content. [notice]
 * says what to review before releasing.
 */
data class MergedReleaseInfo(
    val release: String,
    val taken: List<String>,
    val removed: List<String>,
    val kept: List<String>,
    val merged: List<String> = emptyList(),
    val notice: String? = null,
    /**
     * The catalogs (by alias) the draft declares and, after the merge, no longer uses. Ask the user
     * whether to remove them (`update_catalog_dependencies` without them); never remove them unasked.
     */
    val unusedDependencies: List<String> = emptyList(),
) {
    companion object {
        fun from(merged: MergedRelease, unusedDependencies: List<String> = emptyList()) = MergedReleaseInfo(
            merged.release,
            merged.taken,
            merged.removed,
            merged.kept,
            merged.merged,
            notice = listOfNotNull(
                merged.merged.takeIf { it.isNotEmpty() }?.let {
                    "Merged content is not marked ready: review ${it.joinToString()} with the user and mark it ready before releasing the draft."
                },
                unusedDependencies.takeIf { it.isNotEmpty() }?.let {
                    "The draft no longer uses ${it.joinToString()}: ask the user whether to remove ${if (it.size == 1) "it" else "them"} from its dependencies."
                },
            ).joinToString(" ").ifEmpty { null },
            unusedDependencies = unusedDependencies,
        )
    }
}

/**
 * One entry of a merge on its three sides (`get_merge_sides`), each in the portable catalog format
 * (null where that side does not have it): [base] in the release both come from ([mergeBase]),
 * [draft] (ours) and [release] (theirs). [token] is the preview's, for `merge_release`.
 */
data class MergeSidesInfo(
    val entry: String,
    val mergeBase: String?,
    val base: Map<String, Any?>?,
    val draft: Map<String, Any?>?,
    val release: Map<String, Any?>?,
    val token: String,
) {
    companion object {
        fun from(sides: DraftMergeSides) = MergeSidesInfo(sides.entry, sides.mergeBase, sides.base, sides.draft, sides.release, sides.token)
    }
}
