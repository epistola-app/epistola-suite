package app.epistola.suite.tenantbackup.schema

import org.jdbi.v3.core.Handle

/**
 * The identity of the database schema a backup was taken at — the latest applied Flyway migration
 * version. A faithful tenant backup is a **same-schema "undo"**, never a cross-version migration, so
 * restore refuses an artifact whose stamp does not match the running schema (see
 * [app.epistola.suite.tenantbackup.RestoreTenantBackup]).
 *
 * All modules merge onto one global Flyway namespace (a single `flyway_schema_history`), so the
 * head version is a stable, installation-wide identity. We use the latest *applied* version
 * (`installed_rank DESC`), which is the suite's actual schema head regardless of out-of-order
 * timestamps.
 */
object SchemaStamp {
    /** The current schema head, or [UNKNOWN] if no migration history is present (should not happen at runtime). */
    fun current(handle: Handle): String = handle
        .createQuery(
            "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL " +
                "ORDER BY installed_rank DESC LIMIT 1",
        ).mapTo(String::class.java)
        .findOne()
        .orElse(UNKNOWN)

    const val UNKNOWN = "unknown"
}
