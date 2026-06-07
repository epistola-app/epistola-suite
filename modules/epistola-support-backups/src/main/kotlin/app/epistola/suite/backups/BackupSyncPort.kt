package app.epistola.suite.backups

import app.epistola.suite.common.ids.TenantKey
import java.time.Instant

/**
 * Port for syncing tenant catalog snapshots to an external target.
 *
 * The production implementation (`HubBackupSyncAdapter`) streams snapshots to epistola-hub over
 * gRPC; [NoOpBackupSyncAdapter] is the default when the support tier is disabled. Authentication
 * and the target are installation-wide, so the methods take only the tenant/snapshot in play.
 */
interface BackupSyncPort {
    /** True when a real sync target is wired (i.e. not the no-op). */
    fun isEnabled(): Boolean

    /**
     * True once the target is usable — the installation has registered with the hub and has
     * credentials. Defaults to true for the no-op, which has nothing to wait for.
     */
    fun isReady(): Boolean = true

    /** Uploads a snapshot; returns the hub id and whether it was deduplicated (fingerprint match). */
    fun uploadSnapshot(snapshot: TenantSnapshot): SnapshotUploadResult

    /** Lists a tenant's stored snapshots, newest first. */
    fun listSnapshots(tenantKey: TenantKey): List<RemoteSnapshot>

    /** Downloads one snapshot's archive bytes (for restore). */
    fun downloadSnapshot(
        tenantKey: TenantKey,
        snapshotId: String,
    ): ByteArray

    /** Lists compatibility-check results the company side has recorded for a tenant, newest first. */
    fun listCompatibilityResults(tenantKey: TenantKey): List<CompatibilityCheckResult>
}

data class SnapshotUploadResult(
    val snapshotId: String,
    val deduplicated: Boolean,
)

data class RemoteSnapshot(
    val snapshotId: String,
    val snapshotFingerprint: String,
    val sizeBytes: Long,
    val catalogCount: Int,
    val suiteVersion: String,
    val capturedAt: Instant,
    val createdAt: Instant,
    val isLatest: Boolean,
)

enum class CompatibilityVerdict { PASS, WARN, FAIL, UNKNOWN }

data class CompatibilityCheckResult(
    val tenant: String,
    val targetVersion: String,
    val snapshotId: String?,
    val catalogKey: String?,
    val verdict: CompatibilityVerdict,
    val detail: String?,
    val occurredAt: Instant,
)
