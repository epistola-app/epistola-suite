// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.catalog.commands.ReleaseCatalogVersionResult
import app.epistola.suite.catalog.versions.CatalogVersion
import app.epistola.suite.catalog.versions.DraftNumberAssessment

/**
 * One version of a catalog (contract ADR 0002, amendment 2): a draft (`status` `DRAFT`) or a release
 * (`RELEASED`). A draft has no [version] until it is released: it is the catalog's next version
 * ([isNext], addressed as `next`) or long-term work, named, addressed by its [draftId]. A draft names its
 * [base] release and the releases it is [behind]: released after its base, so it does not contain their
 * changes.
 */
data class CatalogVersionInfo(
    val version: String?,
    val status: String,
    val draftId: String?,
    val isNext: Boolean,
    val name: String?,
    val base: String?,
    val createdFrom: String?,
    val behind: List<BehindInfo>,
    val releasedAt: String?,
    val notes: String?,
    val deployedTo: List<String>,
) {
    /** A release a draft is behind, with what it changed (`type/key` and `added`/`modified`/`removed`). */
    data class BehindInfo(val version: String, val changes: List<String>)

    companion object {
        fun from(version: CatalogVersion) = CatalogVersionInfo(
            version = version.version,
            status = version.status.name,
            draftId = version.draftId?.toString(),
            isNext = version.isNext,
            name = version.name,
            base = version.base,
            createdFrom = version.createdFrom,
            behind = version.behind.map { release -> BehindInfo(release.version, release.changes.map { "${it.type}/${it.key} ${it.change}" }) },
            releasedAt = version.releasedAt?.toString(),
            notes = version.notes,
            deployedTo = version.deployedTo,
        )
    }
}

/**
 * The result of `preview_release_number`: the number the next version would be released as, from the
 * kind of change against the [latest] release ([changeKind], null when there is nothing to compare
 * with), and the [reasons] that make it more than a fix.
 */
data class ReleaseNumberInfo(
    val latest: String?,
    val changeKind: String?,
    val reasons: List<String>,
    val suggested: String,
) {
    companion object {
        fun from(assessment: DraftNumberAssessment) = ReleaseNumberInfo(
            latest = assessment.latest,
            changeKind = assessment.needs?.name,
            reasons = assessment.reasons,
            suggested = assessment.suggested,
        )
    }
}

/** The result of `release_next_version`: the release, and the long-term work now behind it, by name. */
data class CatalogReleaseInfo(
    val version: String,
    val fingerprint: String,
    val previousVersion: String?,
    val releasedAt: String,
    val unchangedContent: Boolean,
    val draftsBehind: List<String>,
) {
    companion object {
        fun from(result: ReleaseCatalogVersionResult) = CatalogReleaseInfo(
            version = result.version,
            fingerprint = result.fingerprint,
            previousVersion = result.previousVersion,
            releasedAt = result.releasedAt.toString(),
            unchangedContent = result.unchangedContent,
            draftsBehind = result.draftsBehind,
        )
    }
}
