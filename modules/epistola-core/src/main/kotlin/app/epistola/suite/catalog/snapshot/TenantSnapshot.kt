// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.snapshot

import app.epistola.suite.catalog.releases.ReleaseChannel
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
    val suiteVersion: String,
    val capturedAt: Instant,
    val catalogCount: Int,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TenantSnapshot) return false
        return tenantKey == other.tenantKey &&
            snapshotFingerprint == other.snapshotFingerprint &&
            suiteVersion == other.suiteVersion &&
            capturedAt == other.capturedAt &&
            catalogCount == other.catalogCount &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = tenantKey.hashCode()
        result = 31 * result + snapshotFingerprint.hashCode()
        result = 31 * result + suiteVersion.hashCode()
        result = 31 * result + capturedAt.hashCode()
        result = 31 * result + catalogCount
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

/**
 * Tenant-level manifest written as `snapshot.json` at the root of the snapshot archive.
 *
 * From schema version 2 the archive also carries `identities.json`: the tenant's identity
 * registry. From schema version 3 each catalog entry says where the catalog comes from and lists its
 * releases, whose archives sit at `catalogs/<key>/releases/<version>.zip`; every member it added is
 * optional, so a schema 2 snapshot reads as one with no sources and no releases. It sits beside the catalog ZIPs rather than inside them because
 * `resource_id` is deliberately absent from catalog exchange -- it is tenant-local, and a snapshot
 * is the one place a tenant's own state travels whole.
 */
data class SnapshotManifest(
    val schemaVersion: Int,
    val tenantKey: String,
    val suiteVersion: String,
    val createdAt: String,
    val snapshotFingerprint: String,
    val catalogs: List<SnapshotCatalogEntry>,
)

/** One catalog's entry in the snapshot manifest; its bytes live at [zipPath] in the archive. */
data class SnapshotCatalogEntry(
    val catalogKey: String,
    /**
     * The catalog's namespace (schema 4): within a tenant a catalog is unique by namespace and key
     * (ADR 0034), so two catalogs with one key are two entries. Null in older snapshots, whose
     * restore reads it from the catalog's archive.
     */
    val namespace: String? = null,
    val name: String,
    /**
     * `SUBSCRIBED` for a catalog with a source, else `AUTHORED`. Catalogs have no type any more
     * (ADR 0032); the field stays because the snapshot format is shared with the hub. Restore
     * ignores it.
     */
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
    /**
     * The catalogs it declares it depends on (schema 4), by address (`namespace/key`): what restore
     * orders by, since a key alone may name two. Empty in older snapshots, which order by
     * [dependsOnCatalogKeys].
     */
    val dependsOnCatalogs: List<String> = emptyList(),
    /**
     * Where the catalog receives releases from (schema 3): its source and how it authenticates. The
     * credential is not in it: a snapshot carries no secrets, so a source that needs one is given
     * it again after a restore. Null for a catalog without a source, and in older snapshots.
     */
    val source: SnapshotCatalogSource? = null,
    /**
     * The release it received last, as `catalogs.installed_*` recorded it (schema 3): read from older
     * snapshots, which may name no [nextBase], and no longer written (a release is a release however
     * it arrived, ADR 0036).
     */
    val installed: SnapshotInstalledRelease? = null,
    /** The release its next release is on (schema 3); null when it is on none. */
    val nextBase: String? = null,
    /**
     * Its releases (schema 3), lowest first, each with its archive at [SnapshotRelease.zipPath] so a
     * restored catalog renders and merges them; empty in older snapshots, which restore with none.
     */
    val releases: List<SnapshotRelease> = emptyList(),
)

/** The catalog [this] names in [tenantKey]; its [SnapshotCatalogEntry.namespace] must be known. */
fun SnapshotCatalogEntry.catalogIn(tenantKey: app.epistola.suite.common.ids.TenantKey): app.epistola.suite.common.ids.CatalogId = app.epistola.suite.common.ids.CatalogId(requireNotNull(namespace) { "Snapshot catalog $catalogKey names no namespace" }, app.epistola.suite.common.ids.CatalogKey.of(catalogKey), app.epistola.suite.common.ids.TenantId(tenantKey))

/** `namespace/key`, as [SnapshotCatalogEntry.dependsOnCatalogs] names it. */
val SnapshotCatalogEntry.address: String get() = "$namespace/$catalogKey"

/** A catalog's source in a snapshot: its URI (`catalogs.source_url`) and auth type, never its credential. */
data class SnapshotCatalogSource(val url: String, val authType: String = "NONE")

/** The release a catalog received last: its number and the publisher's fingerprint. */
data class SnapshotInstalledRelease(val version: String, val fingerprint: String? = null)

/**
 * One release of a catalog in a snapshot: what it is (its number, id, fingerprint and base), when
 * and by whom it was released, and its archive ([zipPath], null when its content could not be
 * exported, and then it is not restored).
 */
data class SnapshotRelease(
    val version: String,
    val releaseId: String? = null,
    val fingerprint: String,
    val baseVersion: String? = null,
    val baseFingerprint: String? = null,
    val releasedAt: String? = null,
    val releasedBy: String? = null,
    val notes: String? = null,
    val zipPath: String? = null,
    /** How it came to be in the tenant (`released_via`, [ReleaseChannel]); null in older snapshots. */
    val releasedVia: String? = null,
    /** Where it came from (`released_from`); null when nowhere, and in older snapshots. */
    val releasedFrom: String? = null,
)
