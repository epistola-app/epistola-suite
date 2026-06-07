package app.epistola.suite.backups

import app.epistola.suite.common.ids.TenantKey
import java.time.Instant

/**
 * A built tenant snapshot: the archive bytes plus the metadata the hub upload needs. The
 * [snapshotFingerprint] is the rolled-up content fingerprint used both for hub-side dedup and
 * the suite-side "did anything change since the last backup?" check.
 */
data class TenantSnapshot(
    val tenantKey: TenantKey,
    val snapshotFingerprint: String,
    val capturedAt: Instant,
    val catalogCount: Int,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TenantSnapshot) return false
        return tenantKey == other.tenantKey &&
            snapshotFingerprint == other.snapshotFingerprint &&
            capturedAt == other.capturedAt &&
            catalogCount == other.catalogCount &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = tenantKey.hashCode()
        result = 31 * result + snapshotFingerprint.hashCode()
        result = 31 * result + capturedAt.hashCode()
        result = 31 * result + catalogCount
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

/** Tenant-level manifest written as `snapshot.json` at the root of the snapshot archive. */
data class SnapshotManifest(
    val schemaVersion: Int,
    val tenantKey: String,
    val createdAt: String,
    val snapshotFingerprint: String,
    val catalogs: List<SnapshotCatalogEntry>,
)

/** One catalog's entry in the snapshot manifest; its bytes live at [zipPath] in the archive. */
data class SnapshotCatalogEntry(
    val catalogKey: String,
    val name: String,
    val type: String,
    val catalogFingerprint: String,
    val version: String?,
    val zipPath: String,
    val zipSizeBytes: Long,
    /**
     * Keys of *other* catalogs this catalog references (a theme/code list/font/stencil in another
     * catalog). Excludes self and the system catalog. Restore imports catalogs in dependency order
     * (each dependency before its dependents) so the database FKs resolve.
     */
    val dependsOnCatalogKeys: List<String> = emptyList(),
)
