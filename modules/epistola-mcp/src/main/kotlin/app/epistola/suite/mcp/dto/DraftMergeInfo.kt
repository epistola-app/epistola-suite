// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.catalog.merge.DraftMergePreview
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
        )

        private val QUIET = setOf(MergeOutcome.UNCHANGED, MergeOutcome.SAME)
    }
}

/**
 * One resource of a merge: [resource] is `type/key`, as a resolution names it. [outcome] is `OURS`
 * (only the draft changed it, it stays), `THEIRS` (only the release did, it is taken) or `CONFLICT`;
 * [draftChange] and [releaseChange] say how each side changed it (`ADDED`, `MODIFIED`, `REMOVED`).
 */
data class MergeResourceInfo(
    val resource: String,
    val name: String?,
    val outcome: String,
    val draftChange: String?,
    val releaseChange: String?,
) {
    companion object {
        fun from(resource: MergeResource) = MergeResourceInfo(
            resource = resource.contentKey,
            name = resource.name,
            outcome = resource.outcome.name,
            draftChange = resource.ours?.name,
            releaseChange = resource.theirs?.name,
        )
    }
}

/** What a merge did, each as `type/key`. */
data class MergedReleaseInfo(
    val release: String,
    val taken: List<String>,
    val removed: List<String>,
    val kept: List<String>,
) {
    companion object {
        fun from(merged: MergedRelease) = MergedReleaseInfo(merged.release, merged.taken, merged.removed, merged.kept)
    }
}
