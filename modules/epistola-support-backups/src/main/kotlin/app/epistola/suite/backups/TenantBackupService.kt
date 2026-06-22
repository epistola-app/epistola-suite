package app.epistola.suite.backups

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.metadata.getAs
import app.epistola.suite.tenantbackup.BuildTenantBackup
import app.epistola.suite.tenantbackup.RestoreTenantBackup
import app.epistola.suite.tenantbackup.TenantBackupCrypto
import app.epistola.suite.tenantbackup.TenantBackupManifest
import app.epistola.suite.tenantbackup.TenantRestoreResult
import app.epistola.suite.tenantbackup.schema.Compatibility
import app.epistola.suite.tenantbackup.schema.RestoreCompatibility
import app.epistola.suite.tenantbackup.schema.SchemaStamp
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * The backups engine: builds a tenant's faithful backup (the `tenantbackup/` primitive in this
 * module), skips
 * storing it when nothing changed since the last backup (fingerprint match cached in
 * `app_metadata`), persists it via [TenantBackupStore], and restores from a stored backup.
 *
 * Must run inside a bound mediator context with a principal that has the tenant's permissions — the
 * build command is `BACKUP_CREATE`-gated and restore is `TENANT_RESTORE`-gated (both held by MANAGER).
 * See `BackupScheduler` / `BackupsHandler`.
 */
@Component
class TenantBackupService(
    private val store: TenantBackupStore,
    private val appMetadata: AppMetadataService,
    private val jdbi: Jdbi,
    private val restoreCompatibility: RestoreCompatibility,
    private val crypto: TenantBackupCrypto,
    private val objectMapper: ObjectMapper,
    @Value("\${epistola.support.backups.retention:30}")
    private val retention: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Builds the tenant backup and stores it, skipping the store when unchanged since the last backup.
     * Passes the cached fingerprint to [BuildTenantBackup] so an unchanged tenant is detected after the
     * dump but **before** the archive is zipped + encrypted — the build returns null and that expensive
     * work is never done (the daily-scheduler steady state).
     */
    fun backupTenant(tenantKey: TenantKey): BackupOutcome {
        val last = appMetadata.getAs<LastBackup>(metadataKey(tenantKey))
        val artifact =
            BuildTenantBackup(tenantKey, skipIfFingerprint = last?.fingerprint).execute()
                ?: run {
                    log.debug("Tenant {} unchanged since last backup; skipping store", tenantKey.value)
                    return BackupOutcome.Unchanged(last!!.fingerprint)
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
     * Decides, for the Backups list, whether each backup can be restored on the running schema, and a
     * short version note. Backward/same backups are classified in memory from a **single**
     * backward-boundary query ([RestoreCompatibility.backwardBoundary]); the rare forward backups
     * (taken on a newer schema — only after a downgrade) have their manifest read so the decision is
     * authoritative rather than an optimistic guess.
     */
    fun restorability(
        tenantKey: TenantKey,
        backups: List<StoredBackup>,
    ): Map<String, Restorable> = jdbi.withHandle<Map<String, Restorable>, Exception> { handle ->
        val live = SchemaStamp.current(handle)
        val boundary = restoreCompatibility.backwardBoundary(handle, live)
        backups.associate { backup ->
            backup.id to
                when {
                    backup.schemaStamp == live -> Restorable(restorable = true, note = null)
                    backup.schemaStamp < live ->
                        if (boundary == null || backup.schemaStamp >= boundary) {
                            Restorable(restorable = true, note = null)
                        } else {
                            Restorable(restorable = false, note = "Older version")
                        }
                    else -> forwardRestorable(handle, tenantKey, backup)
                }
        }
    }

    /** Forward (newer-than-live) backup: read its recorded flags and run the authoritative check. */
    private fun forwardRestorable(
        handle: org.jdbi.v3.core.Handle,
        tenantKey: TenantKey,
        backup: StoredBackup,
    ): Restorable {
        val manifest = loadManifest(tenantKey, backup.id) ?: return Restorable(restorable = false, note = "Newer version")
        val restorable = restoreCompatibility.check(handle, backup.schemaStamp, manifest.appliedMigrations) is Compatibility.Compatible
        return Restorable(restorable = restorable, note = "Newer version")
    }

    /** Reads just the `backup.json` manifest out of a stored artifact; null if it can't be loaded/read. */
    private fun loadManifest(
        tenantKey: TenantKey,
        backupId: String,
    ): TenantBackupManifest? = try {
        val bytes = store.load(tenantKey, backupId) ?: return null
        ZipInputStream(ByteArrayInputStream(crypto.unwrap(bytes))).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "backup.json") {
                    return objectMapper.readValue(zip.readBytes(), TenantBackupManifest::class.java)
                }
                entry = zip.nextEntry
            }
            null
        }
    } catch (e: Exception) {
        log.warn("Could not read manifest for backup {} of tenant {}: {}", backupId, tenantKey.value, e.message)
        null
    }

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

    /** Whether a stored backup can be restored on the running schema, plus a short version note. */
    data class Restorable(
        val restorable: Boolean,
        val note: String?,
    )

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
