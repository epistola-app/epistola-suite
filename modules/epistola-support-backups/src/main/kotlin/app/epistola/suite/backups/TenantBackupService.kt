package app.epistola.suite.backups

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.metadata.getAs
import app.epistola.suite.tenantbackup.BuildTenantBackup
import app.epistola.suite.tenantbackup.RestoreTenantBackup
import app.epistola.suite.tenantbackup.TenantRestoreResult
import app.epistola.suite.time.EpistolaClock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * The backups engine: builds a tenant's faithful backup (the `epistola-core` primitive), skips
 * storing it when nothing changed since the last backup (fingerprint match cached in
 * `app_metadata`), persists it via [TenantBackupStore], and restores from a stored backup.
 *
 * Must run inside a bound mediator context with a principal that has the tenant's permissions — the
 * build/restore commands are `TENANT_SETTINGS`-gated. See `BackupScheduler` / `BackupsHandler`.
 */
@Component
class TenantBackupService(
    private val store: TenantBackupStore,
    private val appMetadata: AppMetadataService,
    @Value("\${epistola.support.backups.retention:30}")
    private val retention: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Builds the tenant backup and stores it, skipping the store when unchanged since the last backup. */
    fun backupTenant(tenantKey: TenantKey): BackupOutcome {
        val artifact = BuildTenantBackup(tenantKey).execute()
        val last = appMetadata.getAs<LastBackup>(metadataKey(tenantKey))
        if (last != null && last.fingerprint == artifact.fingerprint) {
            log.debug("Tenant {} unchanged since last backup; skipping store", tenantKey.value)
            return BackupOutcome.Unchanged(artifact.fingerprint)
        }

        val backupId = store.save(artifact)
        val pruned = store.pruneToRetention(tenantKey, retention)
        appMetadata.setAs(metadataKey(tenantKey), LastBackup(artifact.fingerprint, backupId, EpistolaClock.instant().toEpochMilli()))
        log.info(
            "Stored backup {} for tenant {} ({} tables, {} rows, {} bytes){}",
            backupId,
            tenantKey.value,
            artifact.tableCount,
            artifact.rowCount,
            artifact.bytes.size,
            if (pruned > 0) ", pruned $pruned old" else "",
        )
        return BackupOutcome.Created(backupId, artifact.fingerprint)
    }

    /** Lists a tenant's stored backups, newest first, marking the latest. */
    fun listBackups(tenantKey: TenantKey): List<StoredBackup> = store.list(tenantKey)

    /**
     * Restores the tenant from a stored backup. A **merge**, not a wipe (see [RestoreTenantBackup]):
     * exact version numbers are preserved and document history survives. Clears the cached
     * fingerprint so the next backup is stored even if the tenant matches a prior state.
     */
    fun restoreFromBackup(
        tenantKey: TenantKey,
        backupId: String,
    ): TenantRestoreResult {
        val bytes =
            store.load(tenantKey, backupId)
                ?: throw IllegalArgumentException("No backup '$backupId' for tenant '${tenantKey.value}'")
        val result = RestoreTenantBackup(tenantKey, bytes).execute()
        appMetadata.setAs(metadataKey(tenantKey), LastBackup("", backupId, EpistolaClock.instant().toEpochMilli()))
        log.info("Restored tenant {} from backup {} ({} rows)", tenantKey.value, backupId, result.rowsRestored)
        return result
    }

    private fun metadataKey(tenantKey: TenantKey): String = "$METADATA_PREFIX${tenantKey.value}"

    /** Per-tenant record of the last stored backup, as JSON in `app_metadata`. */
    data class LastBackup(
        val fingerprint: String,
        val backupId: String,
        val storedAtEpochMillis: Long = 0,
    )

    private companion object {
        const val METADATA_PREFIX = "backups.lastBackup."
    }
}

/** Outcome of a per-tenant backup attempt. */
sealed interface BackupOutcome {
    /** A new backup was stored. */
    data class Created(
        val backupId: String,
        val fingerprint: String,
    ) : BackupOutcome

    /** Nothing changed since the last backup; nothing new was stored. */
    data class Unchanged(
        val fingerprint: String,
    ) : BackupOutcome
}
