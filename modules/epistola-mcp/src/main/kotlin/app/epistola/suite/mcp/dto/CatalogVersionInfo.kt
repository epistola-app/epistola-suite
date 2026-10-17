// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.catalog.commands.ReleaseCatalogVersionResult
import app.epistola.suite.catalog.versions.CatalogVersion
import app.epistola.suite.catalog.versions.RenumberWarning

/**
 * One version of a catalog: an open draft (`status` `DRAFT`), which the content tools address with
 * `catalogVersion`, or a release (`RELEASED`). A draft names its [base] release and the releases it is
 * [behind]: those above its base and below its number, whose changes it does not contain.
 */
data class CatalogVersionInfo(
    val version: String,
    val status: String,
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
 * The result of `renumber_catalog_version`: the draft as it is now when it was renumbered, or, when
 * it was not because renumbering has consequences to acknowledge, the [warnings] to show the user
 * before calling again with `acknowledgeWarnings`.
 */
data class CatalogVersionRenumberInfo(
    val renumbered: Boolean,
    val version: CatalogVersionInfo?,
    val warnings: List<RenumberWarningInfo>,
) {
    /** One warning; [reasons] list what it found, each naming a resource (`breaking-changes-in-patch` and its kin). */
    data class RenumberWarningInfo(val kind: String, val message: String, val versions: List<String>, val reasons: List<String>)

    companion object {
        fun warnings(warnings: List<RenumberWarning>) = CatalogVersionRenumberInfo(
            renumbered = false,
            version = null,
            warnings = warnings.map { RenumberWarningInfo(it.kind, it.message, it.versions, it.reasons) },
        )
    }
}

/**
 * The result of `release_catalog_version`: the release, the drafts above it that are now behind it,
 * and the draft the released one carried on as (null when it was discarded because a higher draft is
 * open).
 */
data class CatalogReleaseInfo(
    val version: String,
    val fingerprint: String,
    val previousVersion: String?,
    val releasedAt: String,
    val unchangedContent: Boolean,
    val draftsBehind: List<String>,
    val openedDraft: String?,
) {
    companion object {
        fun from(result: ReleaseCatalogVersionResult) = CatalogReleaseInfo(
            version = result.version,
            fingerprint = result.fingerprint,
            previousVersion = result.previousVersion,
            releasedAt = result.releasedAt.toString(),
            unchangedContent = result.unchangedContent,
            draftsBehind = result.draftsBehind,
            openedDraft = result.openedDraft,
        )
    }
}
