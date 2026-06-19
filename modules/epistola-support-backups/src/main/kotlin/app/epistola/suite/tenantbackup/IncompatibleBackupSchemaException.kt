package app.epistola.suite.tenantbackup

/**
 * Thrown by [RestoreTenantBackup] when a backup's schema is not restore-compatible with the running
 * schema (see [app.epistola.suite.tenantbackup.schema.RestoreCompatibility]). Carries a
 * human-readable [reason] so the UI can explain *why* rather than showing a generic failure.
 */
class IncompatibleBackupSchemaException(
    val reason: String,
) : RuntimeException(reason)
