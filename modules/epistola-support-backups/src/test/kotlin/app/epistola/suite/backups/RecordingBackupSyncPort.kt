package app.epistola.suite.backups

import app.epistola.suite.common.ids.TenantKey

/**
 * In-memory [BackupSyncPort] for tests: records uploads and serves queued snapshots/results,
 * standing in for the hub adapter. Each upload gets a synthetic id and reports `deduplicated`
 * when an identical fingerprint was already uploaded.
 */
class RecordingBackupSyncPort : BackupSyncPort {
    var enabled: Boolean = true
    var ready: Boolean = true

    val uploads = mutableListOf<TenantSnapshot>()
    private val fingerprintToId = mutableMapOf<String, String>()
    private var counter = 0

    /** Reset between tests — the bean is a context-wide singleton. */
    fun reset() {
        enabled = true
        ready = true
        uploads.clear()
        fingerprintToId.clear()
        counter = 0
    }

    override fun isEnabled(): Boolean = enabled

    override fun isReady(): Boolean = ready

    override fun uploadSnapshot(snapshot: TenantSnapshot): SnapshotUploadResult {
        uploads += snapshot
        val existing = fingerprintToId[snapshot.snapshotFingerprint]
        if (existing != null) return SnapshotUploadResult(existing, deduplicated = true)
        val id = "snap-${counter++}"
        fingerprintToId[snapshot.snapshotFingerprint] = id
        return SnapshotUploadResult(id, deduplicated = false)
    }

    override fun listSnapshots(tenantKey: TenantKey): List<RemoteSnapshot> = emptyList()

    override fun downloadSnapshot(
        tenantKey: TenantKey,
        snapshotId: String,
    ): ByteArray = uploads.firstOrNull()?.bytes ?: ByteArray(0)

    override fun listCompatibilityResults(tenantKey: TenantKey): List<CompatibilityCheckResult> = emptyList()
}
