// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.tenantbackup

import app.epistola.suite.common.ids.TenantKey
import java.time.Instant

/**
 * Root manifest written as `backup.json` inside the (then-encrypted) backup archive. The
 * [schemaStamp] is the **restore gate**: a faithful backup is a same-schema "undo", so restore
 * refuses an artifact whose stamp does not match the running schema head.
 */
data class TenantBackupManifest(
    /** Artifact format version (this file's layout), independent of the DB schema stamp. */
    val formatVersion: Int,
    /** The Flyway schema head this backup was taken at — see [app.epistola.suite.tenantbackup.schema.SchemaStamp]. */
    val schemaStamp: String,
    /** The suite build version, for display only. */
    val buildVersion: String,
    val tenantKey: String,
    val createdAt: String,
    /** SHA-256 over the ordered table rows (excluding trigger-managed `updated_at`) and blob bytes. */
    val fingerprint: String,
    val tables: List<BackupTableEntry>,
    val blobs: List<BackupBlobEntry>,
    /**
     * Every migration applied when this backup was built, with its restore-compatibility flags
     * snapshotted from each migration's `backup-restore-compatibility` header at build time. Drives **forward** restore
     * (a newer backup into an older app, which can't read the flags of migrations newer than itself).
     * Null on v1 backups — forward restore is then refused; backward still works from [schemaStamp].
     */
    val appliedMigrations: List<BackupMigration>? = null,
)

/** One applied migration's restore-compatibility flags, recorded in the backup at build time. */
data class BackupMigration(
    val version: String,
    val backward: Boolean,
    val forward: Boolean,
)

/** One table's entry: its row file path, ordering, and the column metadata that drives faithful re-bind. */
data class BackupTableEntry(
    val table: String,
    val ordinal: Int,
    val rowCount: Int,
    val path: String,
    val primaryKey: List<String>,
    val columns: List<BackupColumn>,
)

/** Captured `information_schema` column metadata for one column. */
data class BackupColumn(
    val name: String,
    val dataType: String,
    val udtName: String,
    val nullable: Boolean,
)

/**
 * One asset blob's metadata; its bytes live at [file] in the archive. Identified by
 * its content-addressable `(scope, contentHash)` coordinates (#738).
 */
data class BackupBlobEntry(
    val scope: String,
    val contentHash: String,
    val file: String,
    val contentType: String,
    val sizeBytes: Long,
    val createdAt: String,
)

/**
 * A built tenant backup: the encrypted archive bytes plus the metadata the hub upload and dedup
 * need. [fingerprint] drives the "did anything change since the last backup?" skip.
 */
data class TenantBackupArtifact(
    val tenantKey: TenantKey,
    val schemaStamp: String,
    val buildVersion: String,
    val fingerprint: String,
    val capturedAt: Instant,
    val tableCount: Int,
    val rowCount: Int,
    val blobCount: Int,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TenantBackupArtifact) return false
        return tenantKey == other.tenantKey &&
            fingerprint == other.fingerprint &&
            schemaStamp == other.schemaStamp &&
            capturedAt == other.capturedAt &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = tenantKey.hashCode()
        result = 31 * result + fingerprint.hashCode()
        result = 31 * result + schemaStamp.hashCode()
        result = 31 * result + capturedAt.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}
