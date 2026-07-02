package app.epistola.suite.catalog.migrations

/**
 * Catalog wire-format version errors raised on import, before any resource is
 * bound or installed.
 *
 * All extend [IllegalArgumentException] so the existing import error paths
 * (`CatalogHandler` catches `IllegalArgumentException` → 400; the REST resolver
 * treats it as bad input) surface them as a client error without new wiring.
 * The REST layer additionally maps each to a dedicated RFC 9457 Problem Details
 * type (`CATALOG_SCHEMA_TOO_NEW` / `_TOO_OLD` / `_UNKNOWN`; see
 * `ApiExceptionMappings`).
 */
sealed class CatalogSchemaException(message: String) : IllegalArgumentException(message)

/**
 * The payload's wire schema version is newer than this instance understands.
 * The operator must upgrade Epistola to import it.
 */
class CatalogSchemaTooNewException(
    val version: Int,
    val current: Int,
) : CatalogSchemaException(
    "Catalog wire schema version $version is newer than this instance supports " +
        "(current: $current). Upgrade Epistola to import this catalog.",
)

/**
 * The payload's wire schema version predates the oldest version the migration
 * chain can still upgrade. The operator must re-export from a current source.
 */
class CatalogSchemaTooOldException(
    val version: Int,
    val baseline: Int,
) : CatalogSchemaException(
    "Catalog wire schema version $version predates the oldest supported version " +
        "($baseline). Re-export the catalog from a current source.",
)

/**
 * The payload carries no readable integer `schemaVersion` — it is not a catalog
 * wire payload this instance recognises.
 */
class CatalogSchemaUnknownException(
    detail: String,
) : CatalogSchemaException("Unrecognised catalog wire payload: $detail")
