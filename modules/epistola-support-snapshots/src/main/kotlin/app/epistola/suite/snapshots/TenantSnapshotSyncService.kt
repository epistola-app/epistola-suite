package app.epistola.suite.snapshots

import app.epistola.suite.catalog.snapshot.BuildTenantSnapshot
import app.epistola.suite.catalog.snapshot.RestoreResult
import app.epistola.suite.catalog.snapshot.RestoreTenantSnapshot
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.metadata.getAs
import app.epistola.suite.time.EpistolaClock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * The shared snapshot-sync engine that both support features ride. It builds a tenant snapshot
 * (the `epistola-core` primitive), skips the upload when nothing changed since the last sync (the
 * rolled-up fingerprint matches the value cached in `app_metadata`), and records *when* a current
 * snapshot last reached the hub — the freshness signal the upgrading timer reads to decide whether
 * it needs to make one.
 *
 * Both the daily backup scheduler and the upgrading freshness sweep call [syncTenant], so the last
 * sync time reflects snapshots made for *either* purpose. Must be called inside a bound mediator
 * context with a principal that has the tenant's permissions (the snapshot build/restore are
 * permission-gated commands) — see [snapshotSystemPrincipal].
 */
@Component
class TenantSnapshotSyncService(
    private val port: SnapshotSyncPort,
    private val appMetadata: AppMetadataService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Whether the sync target is wired and ready (support enabled + installation registered). */
    fun isReady(): Boolean = port.isEnabled() && port.isReady()

    /**
     * Builds the tenant snapshot and uploads it, skipping the upload (but still confirming
     * freshness) when the catalogs are unchanged since the last sync. Records the new last-sync
     * state — fingerprint, hub id, and timestamp — so dedup and the freshness check stay accurate.
     */
    fun syncTenant(tenantKey: TenantKey): SnapshotSyncOutcome {
        if (!port.isEnabled()) return SnapshotSyncOutcome.Disabled
        if (!port.isReady()) return SnapshotSyncOutcome.NotReady

        val snapshot = BuildTenantSnapshot(tenantKey).execute()
        val now = EpistolaClock.instant()
        val last = appMetadata.getAs<LastSnapshot>(metadataKey(tenantKey))

        if (last != null && last.fingerprint == snapshot.snapshotFingerprint) {
            // Unchanged: nothing new to upload, but the current state is confirmed present on the
            // hub as of now — stamp the sync time so the freshness check counts it.
            appMetadata.setAs(metadataKey(tenantKey), last.copy(syncedAtEpochMillis = now.toEpochMilli()))
            log.debug("Tenant {} catalogs unchanged since last sync; skipping upload", tenantKey.value)
            return SnapshotSyncOutcome.Unchanged(snapshot.snapshotFingerprint)
        }

        val result = port.uploadSnapshot(snapshot)
        appMetadata.setAs(
            metadataKey(tenantKey),
            LastSnapshot(snapshot.snapshotFingerprint, result.snapshotId, now.toEpochMilli()),
        )
        log.info(
            "Synced tenant {} snapshot ({} catalogs, {} bytes) as {}{}",
            tenantKey.value,
            snapshot.catalogCount,
            snapshot.bytes.size,
            result.snapshotId,
            if (result.deduplicated) " (deduplicated)" else "",
        )
        return SnapshotSyncOutcome.Uploaded(result.snapshotId, result.deduplicated, snapshot.snapshotFingerprint)
    }

    /**
     * When a current snapshot was last synced to the hub for this tenant (uploaded *or* confirmed
     * unchanged), or null if never. The upgrading freshness timer uses this to avoid creating a
     * snapshot when the daily backup already made a recent one.
     */
    fun lastSnapshotAt(tenantKey: TenantKey): Instant? = appMetadata
        .getAs<LastSnapshot>(metadataKey(tenantKey))
        ?.takeIf { it.syncedAtEpochMillis > 0 }
        ?.let { Instant.ofEpochMilli(it.syncedAtEpochMillis) }

    /** Lists a tenant's stored snapshots, newest first. */
    fun listSnapshots(tenantKey: TenantKey): List<RemoteSnapshot> = port.listSnapshots(tenantKey)

    /**
     * Downloads the given snapshot and restores the tenant from it. **Destructive** — see
     * [RestoreTenantSnapshot]. Must run inside a bound mediator context with the tenant's
     * permissions. Clears the cached fingerprint so the next sync re-uploads.
     */
    fun restoreFromSnapshot(
        tenantKey: TenantKey,
        snapshotId: String,
    ): RestoreResult {
        val bytes = port.downloadSnapshot(tenantKey, snapshotId)
        val result = RestoreTenantSnapshot(tenantKey, bytes).execute()
        appMetadata.setAs(metadataKey(tenantKey), LastSnapshot("", snapshotId, EpistolaClock.instant().toEpochMilli()))
        log.info("Restored tenant {} from snapshot {} ({} catalogs)", tenantKey.value, snapshotId, result.restoredCatalogKeys.size)
        return result
    }

    private fun metadataKey(tenantKey: TenantKey): String = "$METADATA_PREFIX${tenantKey.value}"

    /**
     * Per-tenant record of the last synced snapshot, stored as JSON in `app_metadata`.
     * [syncedAtEpochMillis] is epoch millis (not an `Instant`) to keep serialization independent of
     * the JSON time module.
     */
    data class LastSnapshot(
        val fingerprint: String,
        val snapshotId: String,
        val syncedAtEpochMillis: Long = 0,
    )

    private companion object {
        const val METADATA_PREFIX = "snapshots.lastSync."
    }
}

/** Outcome of a per-tenant snapshot sync attempt. */
sealed interface SnapshotSyncOutcome {
    /** The support tier is off (no hub target wired). */
    data object Disabled : SnapshotSyncOutcome

    /** The installation has not finished registering with the hub yet. */
    data object NotReady : SnapshotSyncOutcome

    /** The tenant's catalogs are identical to the last sync; nothing was uploaded. */
    data class Unchanged(
        val fingerprint: String,
    ) : SnapshotSyncOutcome

    /** A snapshot was uploaded (or matched an existing one when [deduplicated]). */
    data class Uploaded(
        val snapshotId: String,
        val deduplicated: Boolean,
        val fingerprint: String,
    ) : SnapshotSyncOutcome
}
