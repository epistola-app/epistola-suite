package app.epistola.suite.tenantbackup.schema

import org.jdbi.v3.core.Handle

/**
 * The identity of the database schema a backup was taken at — the latest applied Flyway migration
 * version. A faithful tenant backup is a **same-schema "undo"**, never a cross-version migration, so
 * restore refuses an artifact whose stamp does not match the running schema (see
 * [app.epistola.suite.tenantbackup.RestoreTenantBackup]).
 *
 * All modules merge onto one global Flyway namespace (a single `flyway_schema_history`), so the
 * head version is a stable, installation-wide identity. We use the greatest applied *version*
 * (`ORDER BY version DESC`), **not** `installed_rank`, so the head is defined by the same ordering
 * [RestoreCompatibility] uses when it compares stamps. This relies on the project convention that
 * every migration version is a fixed-width `YYYYMMDDHHMMSS` timestamp, so lexicographic order equals
 * chronological order (and, with Flyway's default `outOfOrder=false`, equals apply order). A
 * non-conforming version would break that equivalence — `SchemaBackupCompatibilityFileTest` guards it.
 */
object SchemaStamp {
    /** The current schema head, or [UNKNOWN] if no migration history is present (should not happen at runtime). */
    fun current(handle: Handle): String = handle
        .createQuery(
            "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL " +
                "ORDER BY version DESC LIMIT 1",
        ).mapTo(String::class.java)
        .findOne()
        .orElse(UNKNOWN)

    const val UNKNOWN = "unknown"
}
