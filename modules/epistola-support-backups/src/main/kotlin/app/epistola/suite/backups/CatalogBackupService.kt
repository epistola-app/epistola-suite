package app.epistola.suite.backups

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.metadata.getAs
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Builds a tenant snapshot and uploads it to the sync target, skipping the upload when nothing
 * changed since the last backup (the rolled-up fingerprint matches the value cached in
 * `app_metadata`). The hub also dedups by fingerprint, so this is a bandwidth optimisation rather
 * than the correctness boundary.
 *
 * Must be called inside a bound mediator context and with a principal that has the tenant's
 * permissions (the snapshot build is a permission-gated command) — see [app.epistola.suite.backups.backupSystemPrincipal].
 */
@Component
class CatalogBackupService(
    private val port: BackupSyncPort,
    private val appMetadata: AppMetadataService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun backupTenant(tenantKey: TenantKey): BackupOutcome {
        if (!port.isEnabled()) return BackupOutcome.Disabled
        if (!port.isReady()) return BackupOutcome.NotReady

        val snapshot = BuildTenantSnapshot(tenantKey).execute()

        val lastFingerprint = appMetadata.getAs<LastSnapshot>(metadataKey(tenantKey))?.fingerprint
        if (lastFingerprint == snapshot.snapshotFingerprint) {
            log.debug("Tenant {} catalogs unchanged since last backup; skipping upload", tenantKey.value)
            return BackupOutcome.Unchanged(snapshot.snapshotFingerprint)
        }

        val result = port.uploadSnapshot(snapshot)
        appMetadata.setAs(metadataKey(tenantKey), LastSnapshot(snapshot.snapshotFingerprint, result.snapshotId))
        log.info(
            "Backed up tenant {} ({} catalogs, {} bytes) as snapshot {}{}",
            tenantKey.value,
            snapshot.catalogCount,
            snapshot.bytes.size,
            result.snapshotId,
            if (result.deduplicated) " (deduplicated)" else "",
        )
        return BackupOutcome.Uploaded(result.snapshotId, result.deduplicated, snapshot.snapshotFingerprint)
    }

    /** Whether the backup capability is wired and ready (installation registered). */
    fun isReady(): Boolean = port.isEnabled() && port.isReady()

    /** Lists a tenant's stored snapshots, newest first. */
    fun listSnapshots(tenantKey: TenantKey): List<RemoteSnapshot> = port.listSnapshots(tenantKey)

    /** Lists compatibility-check results recorded by the company side for a tenant. */
    fun listCompatibilityResults(tenantKey: TenantKey): List<CompatibilityCheckResult> = port.listCompatibilityResults(tenantKey)

    /**
     * Downloads the given snapshot and restores the tenant from it. **Destructive** — see
     * [RestoreTenantSnapshot]. Must run inside a bound mediator context with the tenant's
     * permissions. Clears the cached fingerprint so the next backup re-uploads.
     */
    fun restoreFromSnapshot(
        tenantKey: TenantKey,
        snapshotId: String,
    ): RestoreResult {
        val bytes = port.downloadSnapshot(tenantKey, snapshotId)
        val result = RestoreTenantSnapshot(tenantKey, bytes).execute()
        appMetadata.setAs(metadataKey(tenantKey), LastSnapshot("", snapshotId))
        log.info("Restored tenant {} from snapshot {} ({} catalogs)", tenantKey.value, snapshotId, result.restoredCatalogKeys.size)
        return result
    }

    private fun metadataKey(tenantKey: TenantKey): String = "$METADATA_PREFIX${tenantKey.value}"

    /** Per-tenant record of the last uploaded snapshot, stored as JSON in `app_metadata`. */
    data class LastSnapshot(
        val fingerprint: String,
        val snapshotId: String,
    )

    private companion object {
        const val METADATA_PREFIX = "backups.lastSnapshot."
    }
}

/** Outcome of a per-tenant backup attempt. */
sealed interface BackupOutcome {
    /** The support tier is off (no hub target wired). */
    data object Disabled : BackupOutcome

    /** The installation has not finished registering with the hub yet. */
    data object NotReady : BackupOutcome

    /** The tenant's catalogs are identical to the last backup; nothing was uploaded. */
    data class Unchanged(
        val fingerprint: String,
    ) : BackupOutcome

    /** A snapshot was uploaded (or matched an existing one when [deduplicated]). */
    data class Uploaded(
        val snapshotId: String,
        val deduplicated: Boolean,
        val fingerprint: String,
    ) : BackupOutcome
}
