// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.catalog.queries.UpgradeDiff

/**
 * Read-only preview of what upgrading a SUBSCRIBED catalog to its source's
 * latest release would do — the same source-vs-source diff the UI shows. Each
 * bucket entry is `"type/slug"`. The upgrade *action* is intentionally not
 * exposed over MCP (read parity only; mirrors the release-action decision).
 */
data class CatalogUpgradeDiffInfo(
    val catalogId: String,
    val previousVersion: String?,
    val newVersion: String,
    /** True when there is anything to apply (added/removed/changed). */
    val upgradeAvailable: Boolean,
    /** Newly published resources — not installed unless explicitly opted in. */
    val added: List<String>,
    /** Resources the new release removed (would be deleted on upgrade). */
    val removed: List<String>,
    /** Installed resources whose published content changed. */
    val changed: List<String>,
    val unchanged: List<String>,
    /** Cross-catalog references that would block the removal set. */
    val conflicts: List<String>,
    /** True when [conflicts] is non-empty — an upgrade would be rejected. */
    val blockedByConflicts: Boolean,
) {
    companion object {
        private fun keys(changes: List<app.epistola.suite.catalog.queries.UpgradeResourceChange>) = changes.map { "${it.type}/${it.slug}" }

        fun from(diff: UpgradeDiff): CatalogUpgradeDiffInfo = CatalogUpgradeDiffInfo(
            catalogId = diff.catalogKey.value,
            previousVersion = diff.previousVersion,
            newVersion = diff.newVersion,
            upgradeAvailable = diff.hasChanges,
            added = keys(diff.added),
            removed = keys(diff.removed),
            changed = keys(diff.changed),
            unchanged = keys(diff.unchanged),
            conflicts = diff.conflicts,
            blockedByConflicts = diff.hasConflicts,
        )
    }
}
