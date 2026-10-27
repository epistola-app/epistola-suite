// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package db.migration.core.frozen

/**
 * The frozen code the 2.0 upgrade's code migrations share (ADR 0035), as one checksum. A code
 * migration's Flyway checksum is its own pinned `CHECKSUM` combined with this, so an installation that
 * ran it refuses to start on an edit of either: of the migration, or of the toolkit it computed with.
 * `CodeMigrationGuardTest` pins every file of the toolkit to its source.
 */
object FrozenToolkit {

    /** CRC32 of this file with this number written as 0; `CodeMigrationGuardTest` checks it. */
    const val CHECKSUM: Int = -1166805231

    private val parts = listOf(
        CHECKSUM,
        CanonicalJson.CHECKSUM,
        DataExampleKeys.CHECKSUM,
        Envelopes.CHECKSUM,
        OneXContent.CHECKSUM,
        ReferenceWalker.CHECKSUM,
        StorageWrites.CHECKSUM,
        TemplateContentV8.CHECKSUM,
    )

    /** [own], a code migration's pinned checksum, combined with every file of the toolkit's. */
    fun pinned(own: Int): Int = parts.fold(own) { acc, part -> 31 * acc + part }
}
