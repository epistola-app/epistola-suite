// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.snapshots

import app.epistola.hub.client.EpistolaHubClient
import app.epistola.hub.client.port.InstallationStore
import app.epistola.hub.proto.v1.DownloadSnapshotRequest
import app.epistola.hub.proto.v1.ListSnapshotsRequest
import app.epistola.hub.proto.v1.SnapshotHeader
import app.epistola.suite.catalog.snapshot.TenantSnapshot
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.snapshots.RemoteSnapshot
import app.epistola.suite.snapshots.SnapshotSyncPort
import app.epistola.suite.snapshots.SnapshotUploadResult
import com.google.protobuf.Timestamp
import java.time.Instant

/**
 * Production [SnapshotSyncPort] that streams tenant catalog snapshots to epistola-hub over gRPC and
 * reads snapshot metadata back. Authentication is installation-wide: [EpistolaHubClient] attaches
 * the registered API key to every call. Wired only when `epistola.support.enabled=true`; otherwise
 * the no-op adapter is used.
 *
 * Hub errors are allowed to propagate (e.g. `HubEntitlementDeniedException` when the installation
 * has no service contract) so callers — the schedulers and the UI — can surface them.
 */
class HubSnapshotSyncAdapter(
    private val client: EpistolaHubClient,
    private val installationStore: InstallationStore,
) : SnapshotSyncPort {
    @Volatile
    private var registered = false

    override fun isEnabled(): Boolean = true

    /**
     * Ready once the installation has registered (credentials persisted). Registration is one-way
     * for the process lifetime, so latch the first positive result and stop hitting the store.
     */
    override fun isReady(): Boolean {
        if (registered) return true
        val ready = installationStore.load() != null
        if (ready) registered = true
        return ready
    }

    override fun uploadSnapshot(snapshot: TenantSnapshot): SnapshotUploadResult {
        val header =
            SnapshotHeader
                .newBuilder()
                .setTenant(snapshot.tenantKey.value)
                .setSnapshotFingerprint(snapshot.snapshotFingerprint)
                .setCatalogCount(snapshot.catalogCount)
                .setSuiteVersion(snapshot.suiteVersion)
                .setCapturedAt(snapshot.capturedAt.toTimestamp())
                .build()
        val response = client.uploadSnapshot(header, snapshot.bytes)
        return SnapshotUploadResult(snapshotId = response.snapshotId, deduplicated = response.deduplicated)
    }

    override fun listSnapshots(tenantKey: TenantKey): List<RemoteSnapshot> {
        val response = client.listSnapshots(ListSnapshotsRequest.newBuilder().setTenant(tenantKey.value).build())
        return response.snapshotsList.map { s ->
            RemoteSnapshot(
                snapshotId = s.snapshotId,
                snapshotFingerprint = s.snapshotFingerprint,
                sizeBytes = s.sizeBytes,
                catalogCount = s.catalogCount,
                suiteVersion = s.suiteVersion,
                capturedAt = s.capturedAt.toInstant(),
                createdAt = s.createdAt.toInstant(),
                isLatest = s.isLatest,
            )
        }
    }

    override fun downloadSnapshot(
        tenantKey: TenantKey,
        snapshotId: String,
    ): ByteArray = client
        .downloadSnapshot(
            DownloadSnapshotRequest.newBuilder().setTenant(tenantKey.value).setSnapshotId(snapshotId).build(),
        ).content
}

private fun Instant.toTimestamp(): Timestamp = Timestamp.newBuilder().setSeconds(epochSecond).setNanos(nano).build()

private fun Timestamp.toInstant(): Instant = Instant.ofEpochSecond(seconds, nanos.toLong())
