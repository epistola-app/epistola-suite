package app.epistola.suite.catalog.commands

import app.epistola.suite.validation.FieldLimits.MAX_NAME_COLUMN_LENGTH
import app.epistola.suite.validation.validate

/**
 * Validates a catalog display name arriving from a remote or ZIP manifest against
 * the `catalogs.name VARCHAR(255)` column ceiling (issue #692).
 *
 * Manifest-sourced writes (`RegisterCatalog`, `UpgradeCatalog`, and the SUBSCRIBED
 * paths of `ImportCatalogZip`) validate at the **column width**, not the tighter
 * interactive [app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH]: a
 * manifest name that fits the column keeps registering, while an over-length one
 * is rejected with a clear [app.epistola.suite.validation.ValidationException]
 * instead of overflowing the column (SQLSTATE 22001 → opaque 500 on REST, silent
 * job failure on hub sync). Rejecting exactly at the column width is
 * behaviour-preserving.
 */
internal fun validateCatalogNameLength(name: String) {
    validate("name", name.length <= MAX_NAME_COLUMN_LENGTH) { "Name must be $MAX_NAME_COLUMN_LENGTH characters or less" }
}
