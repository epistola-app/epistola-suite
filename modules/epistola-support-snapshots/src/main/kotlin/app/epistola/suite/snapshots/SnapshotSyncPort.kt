// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.snapshots

import app.epistola.suite.catalog.snapshot.TenantSnapshot
import app.epistola.suite.common.ids.TenantKey
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Instant

/**
 * Port for syncing tenant catalog snapshots to an external target — the shared transport behind
 * backups (retained snapshots + restore) and upgrading (a fresh snapshot for the company-side
 * compatibility check). The snapshot itself is built/restored in `epistola-core`; this port only
 * moves the bytes.
 *
 * The production implementation (`HubSnapshotSyncAdapter`) streams snapshots to epistola-hub over
 * gRPC; [NoOpSnapshotSyncAdapter] is the default when the support tier is disabled. Authentication
 * and the target are installation-wide, so the methods take only the tenant/snapshot in play.
 */
interface SnapshotSyncPort {
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

/**
 * Fallback [SnapshotSyncPort] used when the support tier is disabled (no hub adapter wired). It
 * reports itself disabled so schedulers and UI never attempt remote calls; list methods return
 * empty and the byte-moving methods are never reached (callers gate on [isEnabled]).
 */
class NoOpSnapshotSyncAdapter : SnapshotSyncPort {
    override fun isEnabled(): Boolean = false

    override fun uploadSnapshot(snapshot: TenantSnapshot): SnapshotUploadResult = error("Snapshot sync target is not configured (epistola.support.enabled=false)")

    override fun listSnapshots(tenantKey: TenantKey): List<RemoteSnapshot> = emptyList()

    override fun downloadSnapshot(
        tenantKey: TenantKey,
        snapshotId: String,
    ): ByteArray = error("Snapshot sync target is not configured (epistola.support.enabled=false)")
}

/**
 * Registers the no-op adapter when the support tier is **off**. Gated on the inverse of the hub
 * adapter's `epistola.support.enabled=true` so the two are mutually exclusive regardless of
 * configuration-class ordering (an `@ConditionalOnMissingBean`-only fallback can race the hub
 * adapter and create both beans). `@ConditionalOnMissingBean` is kept so an explicit/test override
 * still wins.
 */
@Configuration
class SnapshotSyncFallbackConfiguration {
    @Bean
    @ConditionalOnMissingBean(SnapshotSyncPort::class)
    @ConditionalOnProperty(prefix = "epistola.support", name = ["enabled"], havingValue = "false", matchIfMissing = true)
    fun noOpSnapshotSyncAdapter(): SnapshotSyncPort = NoOpSnapshotSyncAdapter()
}
