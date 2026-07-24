// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

/**
 * Raised by `ImportCatalogZip` when importing an **AUTHORED** ZIP whose catalog
 * schema version is below current but a migration chain can bring it to current:
 * migrating mutates the imported content, so the operator must confirm first.
 * Re-run the import with `confirmMigration = true` to proceed.
 *
 * Not a [migrations.CatalogSchemaException] — it is a confirmation request, not a
 * rejection. The UI catches it and renders an "update?" prompt; the REST import
 * confirms implicitly (programmatic, non-interactive).
 */
class CatalogMigrationConfirmationRequiredException(
    val fromVersion: Int,
    val toVersion: Int,
) : RuntimeException(
    "Catalog is at schema version $fromVersion; importing will update it to $toVersion. Confirm to proceed.",
)
