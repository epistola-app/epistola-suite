// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.catalog.CatalogCanonicalizer
import app.epistola.suite.catalog.commands.ReleaseCatalogDraftResult
import app.epistola.suite.catalog.releases.CatalogRelease
import app.epistola.suite.catalog.releases.DraftNumberAssessment

/**
 * One version of a catalog (contract ADR 0002): a draft (`status` `DRAFT`) or a release (`RELEASED`),
 * addressed by its [versionId], which a draft keeps when it is released. A draft has no [version] until
 * then; the [isNext] one is the catalog's next release (also addressed as `next`), a convention of this
 * suite. A draft names its fixed [base] release and the releases it is [behind] for the number it would
 * be released as. A release has a [contentHash] (ADR 0034), its identity: releases with the same one
 * have the same content, whatever their number or catalog. It is null for a draft, and for a release
 * recorded before content hashes until it is computed.
 */
data class CatalogReleaseInfo(
    val version: String?,
    val status: String,
    val versionId: String,
    val isNext: Boolean,
    val name: String?,
    val base: String?,
    val createdFrom: String?,
    val behind: List<BehindInfo>,
    val releasedAt: String?,
    val notes: String?,
    val deployedTo: List<String>,
    val contentHash: String? = null,
    /**
     * How a release came to be in the tenant: `released` (here), `upload`, `api`, `registry`,
     * `exchange`, `source`, `bundled`, `upgrade-1x` or `snapshot`. Null for a draft. A release is a
     * release however it arrived: this describes it, nothing depends on it.
     */
    val releasedVia: String? = null,
    /** Where a release came from, in the terms of [releasedVia]: a source URL, a registry reference, a file name. */
    val releasedFrom: String? = null,
) {
    /** A release a draft is behind, with what it changed (`type/key` and `added`/`modified`/`removed`). */
    data class BehindInfo(val version: String, val changes: List<String>)

    companion object {
        fun from(version: CatalogRelease) = CatalogReleaseInfo(
            version = version.version,
            status = version.status.name,
            versionId = version.versionId,
            isNext = version.isNext,
            name = if (version.status == app.epistola.suite.catalog.releases.CatalogReleaseStatus.DRAFT) version.label else null,
            base = version.base,
            createdFrom = version.createdFrom,
            behind = version.behind.map { release -> BehindInfo(release.version, release.changes.map { "${it.type}/${it.key} ${it.change}" }) },
            releasedAt = version.releasedAt?.toString(),
            notes = version.notes,
            deployedTo = version.deployedTo,
            contentHash = version.contentHash,
            releasedVia = version.releasedVia?.code,
            releasedFrom = version.releasedFrom,
        )
    }
}

/**
 * The result of `preview_release_number`: the number a draft would be released as, from the kind of
 * change against its [base] ([changeKind], null when there is nothing to compare with): the number that
 * needs ([needed]) and the one it gets ([suggested]), and the [reasons] that make it more than a fix.
 */
data class ReleaseNumberInfo(
    val base: String?,
    val changeKind: String?,
    val reasons: List<String>,
    val needed: String,
    val suggested: String,
) {
    companion object {
        fun from(assessment: DraftNumberAssessment) = ReleaseNumberInfo(
            base = assessment.base,
            changeKind = assessment.needs?.name,
            reasons = assessment.reasons,
            needed = assessment.needed,
            suggested = assessment.suggested,
        )
    }
}

/**
 * The result of `release_draft`: the release, by number and by the [versionId] the draft had, with its
 * [contentHash] (ADR 0034), the other drafts it puts behind, by name, and the drafts of other catalogs
 * released along with it.
 */
data class ReleasedDraftInfo(
    val version: String,
    val versionId: String,
    val fingerprint: String,
    val previousVersion: String?,
    val releasedAt: String,
    val unchangedContent: Boolean,
    val draftsBehind: List<String>,
    val releasedDependencies: List<String>,
    val contentHash: String?,
    /** Dependencies declared by name that this release pinned, as `catalog vVersion`. */
    val pinnedDependencies: List<String> = emptyList(),
) {
    companion object {
        fun from(result: ReleaseCatalogDraftResult) = ReleasedDraftInfo(
            version = result.version,
            versionId = result.versionId,
            fingerprint = result.fingerprint,
            previousVersion = result.previousVersion,
            releasedAt = result.releasedAt.toString(),
            unchangedContent = result.unchangedContent,
            draftsBehind = result.draftsBehind,
            releasedDependencies = result.releasedDependencies.map { "${it.catalogKey} v${it.releaseVersion}" },
            // A release is fingerprinted with its content hash (ADR 0034).
            contentHash = result.fingerprint.takeIf(CatalogCanonicalizer::isContentHash),
            pinnedDependencies = result.pinnedDependencies.map { "${it.catalogKey} v${it.releaseVersion}" },
        )
    }
}
