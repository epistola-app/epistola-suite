package app.epistola.suite.upgrading

import app.epistola.suite.catalog.snapshot.TenantSnapshot
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.snapshots.RemoteSnapshot
import app.epistola.suite.snapshots.SnapshotSyncPort
import app.epistola.suite.snapshots.SnapshotUploadResult

/**
 * In-memory [SnapshotSyncPort] for tests: records uploads, standing in for the hub adapter so the
 * freshness sweep can run without the support tier. Each upload gets a synthetic id and reports
 * `deduplicated` when an identical fingerprint was already uploaded.
 */
class RecordingSnapshotSyncPort : SnapshotSyncPort {
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

    fun uploadsFor(tenantKey: TenantKey): List<TenantSnapshot> = uploads.filter { it.tenantKey == tenantKey }

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
    ): ByteArray = uploads.firstOrNull { it.tenantKey == tenantKey }?.bytes ?: ByteArray(0)
}
