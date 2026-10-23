// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

/**
 * What a ZIP import should do about the payload's catalog **schema version**,
 * decided after the wire migrator has parsed it. A pure function of the source's
 * pre-migration version, the post-migration version, the current version, and
 * whether the operator already confirmed an update. It is the same for every
 * import, whichever catalog receives it (ADR 0032: catalogs have no type).
 *
 * Rules (a ZIP is a source-less transport; we never store a per-catalog schema
 * version — once imported, content is at the current version):
 *
 * - **At or above current** → [IMPORT] (a too-new payload is already rejected by
 *   the migrator's gate before we get here).
 * - **Below current, no migration path** (the migrator left it sub-current) →
 *   [BLOCK_TOO_OLD]. Nothing can bring it to current — re-export from a current
 *   source.
 * - **Below current, a migration path exists** (the migrator upgraded the payload
 *   to current) → [CONFIRM_MIGRATION] until the operator confirms, then [IMPORT].
 *   Migrating mutates the imported content, so we ask first; imports nobody is
 *   asked about confirm up front.
 */
enum class CatalogImportSchemaAction {
    IMPORT,
    BLOCK_TOO_OLD,
    CONFIRM_MIGRATION,
    ;

    companion object {
        fun decide(
            sourceVersion: Int,
            migratedVersion: Int,
            current: Int,
            confirmed: Boolean,
        ): CatalogImportSchemaAction = when {
            sourceVersion >= current -> IMPORT
            migratedVersion < current -> BLOCK_TOO_OLD
            confirmed -> IMPORT
            else -> CONFIRM_MIGRATION
        }
    }
}
